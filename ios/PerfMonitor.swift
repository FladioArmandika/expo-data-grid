import QuartzCore
import UIKit

/// What the perf monitor needs from a renderer.
protocol PerfMonitorTarget: AnyObject {
  /// Fraction of the viewport that should show a cell but doesn't, right now.
  func currentBlankFraction() -> Double
  /// True while the content is moving (drag, deceleration, or benchmark).
  var isMoving: Bool { get }
  /// True once at least one cell has been drawn.
  var hasDrawnContent: Bool { get }
}

struct PerfSample {
  var fps: Double = 0
  var refreshRate: Double = 0
  var droppedFrames: Int = 0
  var blankNow: Double = 0
  var blankMovingAvg: Double = 0
  var blankMax: Double = 0
  var residentBytes: UInt64 = 0
  var timeToFirstFrameMs: Double?
}

/// Frame statistics accumulated over a window (used by the benchmark).
struct FrameStats {
  var frames = 0
  var dropped = 0
  var elapsed: CFTimeInterval = 0
  var blankSum = 0.0
  var blankMax = 0.0
  var blankSamples = 0
  var peakResident: UInt64 = 0
  var worstFrameMs = 0.0

  mutating func reset() { self = FrameStats() }
}

/// Weak proxy so the display link doesn't retain its owner.
private final class DisplayLinkProxy: NSObject {
  weak var owner: PerfMonitor?
  @objc func tick(_ link: CADisplayLink) { owner?.tick(link) }
}

/// Measures UI-thread frame pacing with CADisplayLink. A tick that arrives late means the main thread
/// missed a frame. This does not see render-server hitches; Instruments is needed for those.
final class PerfMonitor {
  weak var target: PerfMonitorTarget?
  var onSample: ((PerfSample) -> Void)?
  /// Called after measurement on every frame; the benchmark driver uses it to move the content.
  var onFrame: ((CADisplayLink) -> Void)?

  private var link: CADisplayLink?
  private var lastTimestamp: CFTimeInterval = 0
  private let createdAt = CACurrentMediaTime()
  private(set) var timeToFirstFrameMs: Double?

  // Rolling window for the overlay.
  private var windowStart: CFTimeInterval = 0
  private var windowFrames = 0
  private var sample = PerfSample()
  private var movingBlankSum = 0.0
  private var movingBlankCount = 0

  // Accumulator for an explicit measurement run.
  private(set) var stats = FrameStats()
  private var collecting = false

  func start() {
    guard link == nil else { return }
    let proxy = DisplayLinkProxy()
    proxy.owner = self
    let link = CADisplayLink(target: proxy, selector: #selector(DisplayLinkProxy.tick(_:)))
    // Ask for the panel's max rate. On ProMotion iPhones the app also needs
    // CADisableMinimumFrameDurationOnPhone = true in Info.plist to get 120 Hz.
    let maxFps = Float(UIScreen.main.maximumFramesPerSecond)
    link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: maxFps, preferred: maxFps)
    link.add(to: .main, forMode: .common)
    self.link = link
  }

  func stop() {
    link?.invalidate()
    link = nil
    lastTimestamp = 0
  }

  func beginCollecting() {
    stats.reset()
    collecting = true
  }

  func endCollecting() -> FrameStats {
    collecting = false
    return stats
  }

  fileprivate func tick(_ link: CADisplayLink) {
    let now = link.timestamp
    let expected = max(link.targetTimestamp - link.timestamp, 1.0 / 240.0)
    var droppedThisTick = 0
    var frameMs = 0.0
    if lastTimestamp > 0 {
      let dt = now - lastTimestamp
      frameMs = dt * 1000
      droppedThisTick = max(0, Int((dt / expected).rounded()) - 1)
    }
    lastTimestamp = now

    let moving = target?.isMoving ?? false
    let blank = target?.currentBlankFraction() ?? 0

    if timeToFirstFrameMs == nil, target?.hasDrawnContent == true, blank == 0 {
      timeToFirstFrameMs = (now - createdAt) * 1000
      sample.timeToFirstFrameMs = timeToFirstFrameMs
    }

    sample.droppedFrames += droppedThisTick
    sample.blankNow = blank
    if moving {
      movingBlankSum += blank
      movingBlankCount += 1
      sample.blankMax = max(sample.blankMax, blank)
    }

    if collecting {
      stats.frames += 1
      stats.dropped += droppedThisTick
      stats.elapsed += frameMs / 1000
      stats.blankSum += blank
      stats.blankSamples += 1
      stats.blankMax = max(stats.blankMax, blank)
      stats.worstFrameMs = max(stats.worstFrameMs, frameMs)
    }

    windowFrames += 1
    if windowStart == 0 { windowStart = now }
    if now - windowStart >= 0.5 {
      sample.fps = Double(windowFrames) / (now - windowStart)
      sample.refreshRate = 1.0 / expected
      sample.blankMovingAvg = movingBlankCount > 0 ? movingBlankSum / Double(movingBlankCount) : 0
      sample.residentBytes = PerfMonitor.residentBytes()
      if collecting { stats.peakResident = max(stats.peakResident, sample.residentBytes) }
      windowStart = now
      windowFrames = 0
      onSample?(sample)
    }

    onFrame?(link)
  }

  func resetCounters() {
    sample.droppedFrames = 0
    sample.blankMax = 0
    movingBlankSum = 0
    movingBlankCount = 0
  }

  /// Physical footprint, the number Xcode's memory gauge and jetsam use.
  static func residentBytes() -> UInt64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
      $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
        task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
      }
    }
    return result == KERN_SUCCESS ? UInt64(info.phys_footprint) : 0
  }
}

/// Small native overlay drawn above the grid. Updates twice a second so it costs almost nothing.
final class PerfOverlayView: UIView {
  private let label = UILabel()
  var rendererName = ""

  override init(frame: CGRect) {
    super.init(frame: frame)
    isUserInteractionEnabled = false
    backgroundColor = UIColor.black.withAlphaComponent(0.72)
    layer.cornerRadius = 8
    label.numberOfLines = 0
    label.font = UIFont.monospacedSystemFont(ofSize: 11, weight: .medium)
    label.textColor = .white
    addSubview(label)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  func update(_ s: PerfSample) {
    let mb = Double(s.residentBytes) / 1_048_576
    let ttff = s.timeToFirstFrameMs.map { String(format: "%.0f ms", $0) } ?? "–"
    label.text = """
    \(rendererName)
    FPS   \(String(format: "%5.1f", s.fps)) / \(String(format: "%.0f", s.refreshRate))
    Drop  \(s.droppedFrames)
    Blank \(String(format: "%.2f", s.blankMovingAvg * 100))% avg  \(String(format: "%.2f", s.blankMax * 100))% max
    Mem   \(String(format: "%.0f", mb)) MB
    TTFF  \(ttff)
    """
    setNeedsLayout()
  }

  override func sizeThatFits(_ size: CGSize) -> CGSize {
    let s = label.sizeThatFits(CGSize(width: 260, height: 400))
    return CGSize(width: s.width + 16, height: s.height + 12)
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    label.frame = bounds.insetBy(dx: 8, dy: 6)
  }
}

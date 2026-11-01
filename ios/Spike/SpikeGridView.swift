import ExpoModulesCore
import UIKit

/// Host view for the Milestone 0 spike. Mounts one rendering candidate, the perf overlay
/// and the scripted benchmark.
final class SpikeGridView: ExpoView, GridDataListener {
  let onBenchmarkResult = EventDispatcher()
  let onFirstFrame = EventDispatcher()

  private var renderer: GridRenderer?
  private var rendererId = "A"
  private var monitor: PerfMonitor?
  private let overlay = PerfOverlayView()
  private let driver = BenchmarkDriver()
  private var lastBenchmarkRunId = 0
  private var firstFrameSent = false
  private var settleUntil: CFTimeInterval = 0
  private var pendingBenchmark = false

  private var geometry = GridGeometry()
  private var data: GridData?

  var showOverlay = false {
    didSet { overlay.isHidden = !showOverlay }
  }

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    overlay.isHidden = true
    addSubview(overlay)
    driver.onFinish = { [weak self] in self?.finishBenchmark() }
    makeRenderer()
  }

  deinit {
    monitor?.stop()
    data?.removeListener(self)
  }

  // MARK: Props

  func setData(_ newData: GridData?) {
    data?.removeListener(self)
    data = newData
    newData?.addListener(self)
    syncGeometryFromData()
    renderer?.data = newData
  }

  func setRenderer(_ id: String) {
    guard id != rendererId else { return }
    rendererId = id
    makeRenderer()
  }

  func updateGeometry(_ change: (inout GridGeometry) -> Void) {
    change(&geometry)
    renderer?.geometry = geometry
  }

  func setBenchmarkRunId(_ id: Int) {
    guard id != lastBenchmarkRunId else { return }
    lastBenchmarkRunId = id
    if id > 0 { startBenchmark() }
  }

  func gridDataDidChange(_ data: GridData) {
    syncGeometryFromData()
    renderer?.reloadAll()
  }

  private func syncGeometryFromData() {
    geometry.rowCount = data?.rowCount ?? 0
    geometry.columnCount = data?.columnCount ?? 0
    renderer?.geometry = geometry
  }

  // MARK: Renderer lifecycle

  private func makeRenderer() {
    monitor?.stop()
    renderer?.removeFromSuperview()

    let r: GridRenderer
    if rendererId == "B" {
      r = LayerGridRenderer(frame: bounds)
    } else {
      r = CollectionGridRenderer(frame: bounds)
    }
    r.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    r.geometry = geometry
    r.data = data
    insertSubview(r, belowSubview: overlay)
    renderer = r
    overlay.rendererName = r.displayName
    firstFrameSent = false

    let m = PerfMonitor()
    m.target = r
    m.onSample = { [weak self] sample in
      guard let self else { return }
      if self.showOverlay {
        self.overlay.update(sample)
        self.setNeedsLayout()
      }
      if !self.firstFrameSent, let ttff = sample.timeToFirstFrameMs {
        self.firstFrameSent = true
        self.onFirstFrame(["renderer": self.rendererId, "timeToFirstFrameMs": ttff])
      }
    }
    m.onFrame = { [weak self] link in self?.driveBenchmark(link) }
    m.start()
    monitor = m
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    renderer?.frame = bounds
    let size = overlay.sizeThatFits(bounds.size)
    let inset = safeAreaInsets
    overlay.frame = CGRect(x: bounds.width - size.width - 8 - inset.right, y: bounds.height - size.height - 8 - inset.bottom, width: size.width, height: size.height)
  }

  // MARK: Benchmark

  private var maxOffset: CGPoint {
    CGPoint(
      x: max(0, geometry.contentWidth - bounds.width),
      y: max(0, geometry.contentHeight - bounds.height)
    )
  }

  private func startBenchmark() {
    guard let renderer, geometry.rowCount > 0 else { return }
    driver.cancel()
    let middle = CGPoint(x: 0, y: min(geometry.rowY(geometry.rowCount / 2), maxOffset.y))
    renderer.setContentOffset(middle)
    renderer.benchmarkRunning = true
    // Let the jump settle for half a second before measuring.
    settleUntil = CACurrentMediaTime() + 0.5
    pendingBenchmark = true
  }

  private func driveBenchmark(_ link: CADisplayLink) {
    guard let renderer, let monitor else { return }
    if pendingBenchmark {
      if link.timestamp < settleUntil { return }
      pendingBenchmark = false
      monitor.resetCounters()
      monitor.beginCollecting()
      driver.start(from: renderer.contentOffset, now: link.timestamp)
      return
    }
    if let p = driver.offset(at: link.targetTimestamp, current: renderer.contentOffset, maxOffset: maxOffset) {
      renderer.setContentOffset(p)
    }
  }

  private func finishBenchmark() {
    guard let monitor else { return }
    renderer?.benchmarkRunning = false
    let s = monitor.endCollecting()
    let refresh = Double(UIScreen.main.maximumFramesPerSecond)
    onBenchmarkResult([
      "platform": "ios",
      "renderer": rendererId,
      "rendererName": renderer?.displayName ?? "",
      "rows": geometry.rowCount,
      "columns": geometry.columnCount,
      "frames": s.frames,
      "droppedFrames": s.dropped,
      "durationMs": s.elapsed * 1000,
      "avgFps": s.elapsed > 0 ? Double(s.frames) / s.elapsed : 0,
      "refreshRate": refresh,
      "worstFrameMs": s.worstFrameMs,
      "blankAvgPct": s.blankSamples > 0 ? s.blankSum / Double(s.blankSamples) * 100 : 0,
      "blankMaxPct": s.blankMax * 100,
      "blankFramesPct": s.blankSamples > 0 ? Double(s.blankFrames) / Double(s.blankSamples) * 100 : 0,
      "peakResidentMB": Double(max(s.peakResident, PerfMonitor.residentBytes())) / 1_048_576,
      "timeToFirstFrameMs": monitor.timeToFirstFrameMs ?? -1,
    ])
  }
}

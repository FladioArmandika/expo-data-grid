import QuartzCore
import UIKit

/// Scripted, time-based flings so every candidate gets exactly the same motion.
/// Position is a function of elapsed time (not frame count), so a renderer that drops frames
/// still covers the same distance and gets measured on the same content.
struct FlingSegment {
  var direction: CGVector // unit-ish direction
  var velocity: CGFloat // starting velocity, pt/s
}

final class BenchmarkDriver {
  /// Same deceleration curve as UIScrollView.DecelerationRate.normal (0.998 per ms).
  static let decelerationPerMs: CGFloat = 0.998
  static let pauseBetweenFlings: CFTimeInterval = 0.15

  /// The script: jump to the middle of the data (row ~500k) to exercise large offsets,
  /// then 4 vertical flings down, 2 up, 2 horizontal right, 1 left, 2 diagonal.
  static let script: [FlingSegment] = {
    let down = CGVector(dx: 0, dy: 1), up = CGVector(dx: 0, dy: -1)
    let right = CGVector(dx: 1, dy: 0), left = CGVector(dx: -1, dy: 0)
    let diag = CGVector(dx: 0.6, dy: 0.8)
    return [
      FlingSegment(direction: down, velocity: 8000),
      FlingSegment(direction: down, velocity: 8000),
      FlingSegment(direction: down, velocity: 8000),
      FlingSegment(direction: down, velocity: 8000),
      FlingSegment(direction: up, velocity: 8000),
      FlingSegment(direction: up, velocity: 8000),
      FlingSegment(direction: right, velocity: 5000),
      FlingSegment(direction: right, velocity: 5000),
      FlingSegment(direction: left, velocity: 5000),
      FlingSegment(direction: diag, velocity: 7000),
      FlingSegment(direction: diag, velocity: 7000),
    ]
  }()

  private let k = BenchmarkDriver.decelerationPerMs
  private var segmentIndex = 0
  private var segmentStart: CFTimeInterval = 0
  private var segmentOrigin = CGPoint.zero
  private(set) var isRunning = false
  private(set) var startedAt: CFTimeInterval = 0

  var onFinish: (() -> Void)?

  /// Duration (s) until velocity falls below 20 pt/s.
  private func duration(for v0: CGFloat) -> CFTimeInterval {
    let ms = log(20 / v0) / log(k)
    return CFTimeInterval(ms / 1000)
  }

  /// Distance travelled after `t` seconds.
  private func distance(v0: CGFloat, t: CFTimeInterval) -> CGFloat {
    let ms = CGFloat(t * 1000)
    // integral of v0 * k^t dt, t in ms, v0 in pt/ms
    return (v0 / 1000) * (pow(k, ms) - 1) / log(k)
  }

  func start(from origin: CGPoint, now: CFTimeInterval) {
    segmentIndex = 0
    segmentStart = now
    segmentOrigin = origin
    startedAt = now
    isRunning = true
  }

  func cancel() { isRunning = false }

  /// Returns the offset for this frame, or nil when finished.
  func offset(at now: CFTimeInterval, current: CGPoint, maxOffset: CGPoint) -> CGPoint? {
    guard isRunning else { return nil }
    let segment = BenchmarkDriver.script[segmentIndex]
    let t = now - segmentStart
    let total = duration(for: segment.velocity)
    let d = distance(v0: segment.velocity, t: min(t, total))
    var p = CGPoint(x: segmentOrigin.x + segment.direction.dx * d, y: segmentOrigin.y + segment.direction.dy * d)
    p.x = min(max(p.x, 0), maxOffset.x)
    p.y = min(max(p.y, 0), maxOffset.y)

    if t >= total + BenchmarkDriver.pauseBetweenFlings {
      segmentIndex += 1
      segmentStart = now
      segmentOrigin = p
      if segmentIndex >= BenchmarkDriver.script.count {
        isRunning = false
        onFinish?()
        return nil
      }
    }
    return p
  }
}

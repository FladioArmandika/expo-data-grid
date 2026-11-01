package expo.modules.datagrid.spike

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * Scripted, time-based flings. Same script, curve and dp velocities as the iOS driver
 * (UIScrollView "normal" deceleration, 0.998 per ms), so numbers are comparable across platforms.
 * Position is a function of elapsed time, so a renderer that drops frames still covers the same ground.
 */
class BenchmarkDriver(private val density: Float) {
  private class Segment(val dx: Double, val dy: Double, val velocityDp: Double)

  companion object {
    const val DECELERATION_PER_MS = 0.998
    const val PAUSE_NANOS = 150_000_000L

    private val SCRIPT = listOf(
      Segment(0.0, 1.0, 8000.0), Segment(0.0, 1.0, 8000.0), Segment(0.0, 1.0, 8000.0), Segment(0.0, 1.0, 8000.0),
      Segment(0.0, -1.0, 8000.0), Segment(0.0, -1.0, 8000.0),
      Segment(1.0, 0.0, 5000.0), Segment(1.0, 0.0, 5000.0), Segment(-1.0, 0.0, 5000.0),
      Segment(0.6, 0.8, 7000.0), Segment(0.6, 0.8, 7000.0),
    )
  }

  private var index = 0
  private var segmentStart = 0L
  private var originX = 0.0
  private var originY = 0.0
  var isRunning = false
    private set
  var onFinish: (() -> Unit)? = null

  private fun durationNanos(v0: Double): Long = (ln(20.0 / v0) / ln(DECELERATION_PER_MS) * 1e6).toLong()

  private fun distanceDp(v0: Double, nanos: Long): Double {
    val ms = nanos / 1e6
    return (v0 / 1000.0) * (DECELERATION_PER_MS.pow(ms) - 1) / ln(DECELERATION_PER_MS)
  }

  fun start(x: Double, y: Double, now: Long) {
    index = 0
    segmentStart = now
    originX = x
    originY = y
    isRunning = true
  }

  fun cancel() {
    isRunning = false
  }

  /** Offset (px) for the frame at [now], or null when the script is done. */
  fun offsetAt(now: Long, maxX: Double, maxY: Double): DoubleArray? {
    if (!isRunning) return null
    val s = SCRIPT[index]
    val t = now - segmentStart
    val total = durationNanos(s.velocityDp)
    val d = distanceDp(s.velocityDp, min(t, total)) * density
    val px = (originX + s.dx * d).coerceIn(0.0, maxX)
    val py = (originY + s.dy * d).coerceIn(0.0, maxY)
    if (t >= total + PAUSE_NANOS) {
      index++
      segmentStart = now
      originX = px
      originY = py
      if (index >= SCRIPT.size) {
        isRunning = false
        onFinish?.invoke()
        return null
      }
    }
    return doubleArrayOf(px, py)
  }
}

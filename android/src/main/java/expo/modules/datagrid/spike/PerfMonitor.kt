package expo.modules.datagrid.spike

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.hardware.display.DisplayManager
import android.system.Os
import android.system.OsConstants
import android.view.Display
import android.widget.TextView
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.roundToInt

interface PerfMonitorTarget {
  /** Fraction of the viewport that should show a cell but doesn't, right now. */
  fun currentBlankFraction(): Double
  /** True while content is moving (drag, fling, benchmark). */
  val isMoving: Boolean
  /** True once at least one body cell is on screen. */
  val hasDrawnContent: Boolean
}

data class PerfSample(
  var fps: Double = 0.0,
  var refreshRate: Double = 0.0,
  var droppedFrames: Int = 0,
  var blankNow: Double = 0.0,
  var blankMovingAvg: Double = 0.0,
  var blankMax: Double = 0.0,
  var residentBytes: Long = 0,
  var javaHeapBytes: Long = 0,
  var timeToFirstFrameMs: Double? = null,
)

class FrameStats {
  var frames = 0
  var dropped = 0
  var elapsedNanos = 0L
  var blankSum = 0.0
  var blankMax = 0.0
  var blankSamples = 0
  /** Frames where more than 1% of the viewport was blank (TableScrollBench's "some"). */
  var blankFrames = 0
  var peakResident = 0L
  var worstFrameMs = 0.0
}

/**
 * Frame pacing from Choreographer. A callback that arrives late means the UI thread missed vsync.
 * RenderThread hitches aren't visible here; use `adb shell dumpsys gfxinfo <pkg> framestats` for those.
 */
class PerfMonitor(context: Context) : Choreographer.FrameCallback {
  var target: PerfMonitorTarget? = null
  var onSample: ((PerfSample) -> Unit)? = null
  /** Called after measurement on every frame with the frame time in nanos. */
  var onFrame: ((Long) -> Unit)? = null

  private val createdAt = SystemClock.elapsedRealtimeNanos()
  var timeToFirstFrameMs: Double? = null
    private set

  val refreshRate: Double = displayRefreshRate(context)
  private val frameNanos = (1e9 / refreshRate).toLong()

  private var running = false
  private var lastFrame = 0L
  private var windowStart = 0L
  private var windowFrames = 0
  private val sample = PerfSample(refreshRate = refreshRate)
  private var movingBlankSum = 0.0
  private var movingBlankCount = 0

  private var collecting = false
  var stats = FrameStats()
    private set

  @Volatile private var residentBytes = 0L
  private val memoryThread = HandlerThread("expo-data-grid-perf").also { it.start() }
  private val memoryHandler = Handler(memoryThread.looper)
  private val memoryPoll = object : Runnable {
    override fun run() {
      residentBytes = readResidentBytes()
      memoryHandler.postDelayed(this, 500)
    }
  }

  fun start() {
    if (running) return
    running = true
    lastFrame = 0
    Choreographer.getInstance().postFrameCallback(this)
    memoryHandler.post(memoryPoll)
  }

  fun stop() {
    running = false
    Choreographer.getInstance().removeFrameCallback(this)
    memoryHandler.removeCallbacksAndMessages(null)
  }

  fun dispose() {
    stop()
    memoryThread.quitSafely()
  }

  fun beginCollecting() {
    stats = FrameStats()
    collecting = true
  }

  fun endCollecting(): FrameStats {
    collecting = false
    stats.peakResident = max(stats.peakResident, residentBytes)
    return stats
  }

  fun resetCounters() {
    sample.droppedFrames = 0
    sample.blankMax = 0.0
    movingBlankSum = 0.0
    movingBlankCount = 0
  }

  override fun doFrame(frameTimeNanos: Long) {
    if (!running) return
    var dropped = 0
    var dt = 0L
    if (lastFrame > 0) {
      dt = frameTimeNanos - lastFrame
      dropped = max(0, (dt.toDouble() / frameNanos).roundToInt() - 1)
    }
    lastFrame = frameTimeNanos

    val t = target
    val moving = t?.isMoving ?: false
    val blank = t?.currentBlankFraction() ?: 0.0

    if (timeToFirstFrameMs == null && t?.hasDrawnContent == true && blank == 0.0) {
      timeToFirstFrameMs = (frameTimeNanos - createdAt) / 1e6
      sample.timeToFirstFrameMs = timeToFirstFrameMs
    }

    sample.droppedFrames += dropped
    sample.blankNow = blank
    if (moving) {
      movingBlankSum += blank
      movingBlankCount++
      sample.blankMax = max(sample.blankMax, blank)
    }
    if (collecting) {
      stats.frames++
      stats.dropped += dropped
      stats.elapsedNanos += dt
      stats.blankSum += blank
      stats.blankSamples++
      if (blank > 0.01) stats.blankFrames++
      stats.blankMax = max(stats.blankMax, blank)
      stats.worstFrameMs = max(stats.worstFrameMs, dt / 1e6)
      stats.peakResident = max(stats.peakResident, residentBytes)
    }

    windowFrames++
    if (windowStart == 0L) windowStart = frameTimeNanos
    if (frameTimeNanos - windowStart >= 500_000_000L) {
      sample.fps = windowFrames * 1e9 / (frameTimeNanos - windowStart)
      sample.blankMovingAvg = if (movingBlankCount > 0) movingBlankSum / movingBlankCount else 0.0
      sample.residentBytes = residentBytes
      val rt = Runtime.getRuntime()
      sample.javaHeapBytes = rt.totalMemory() - rt.freeMemory()
      windowStart = frameTimeNanos
      windowFrames = 0
      onSample?.invoke(sample)
    }

    onFrame?.invoke(frameTimeNanos)
    Choreographer.getInstance().postFrameCallback(this)
  }

  companion object {
    /** DisplayManager works with any context; Context.getDisplay() throws for non-visual contexts. */
    fun displayRefreshRate(context: Context): Double {
      val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
      return (dm.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f).toDouble().coerceAtLeast(30.0)
    }

    /** RSS from /proc/self/statm (second field, in pages). Cheap enough to poll. */
    private val pageSize: Long = try { Os.sysconf(OsConstants._SC_PAGESIZE) } catch (e: Exception) { 4096L }

    fun readResidentBytes(): Long = try {
      RandomAccessFile("/proc/self/statm", "r").use { f ->
        val parts = f.readLine().trim().split(' ')
        parts[1].toLong() * pageSize
      }
    } catch (e: Exception) {
      0L
    }
  }
}

class PerfOverlayView(context: Context) : TextView(context) {
  var rendererName = ""

  init {
    setTextColor(Color.WHITE)
    typeface = Typeface.MONOSPACE
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    val pad = (8 * resources.displayMetrics.density).toInt()
    setPadding(pad, pad / 2, pad, pad / 2)
    background = GradientDrawable().apply {
      setColor(Color.argb(184, 0, 0, 0))
      cornerRadius = 8 * resources.displayMetrics.density
    }
    isClickable = false
    isFocusable = false
  }

  fun update(s: PerfSample) {
    val mb = s.residentBytes / 1048576.0
    val heap = s.javaHeapBytes / 1048576.0
    val ttff = s.timeToFirstFrameMs?.let { "%.0f ms".format(it) } ?: "–"
    text = buildString {
      appendLine(rendererName)
      appendLine("FPS   %5.1f / %.0f".format(s.fps, s.refreshRate))
      appendLine("Drop  ${s.droppedFrames}")
      appendLine("Blank %.2f%% avg  %.2f%% max".format(s.blankMovingAvg * 100, s.blankMax * 100))
      appendLine("RSS   %.0f MB  heap %.0f MB".format(mb, heap))
      append("TTFF  $ttff")
    }
  }
}

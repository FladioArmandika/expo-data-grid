package expo.modules.datagrid.spike

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import expo.modules.datagrid.GridData
import expo.modules.datagrid.GridDataListener
import expo.modules.datagrid.GridGeometry
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlin.math.roundToInt

/** Host view for the Milestone 0 spike: one candidate renderer, the perf overlay and the benchmark. */
@SuppressLint("ViewConstructor")
class SpikeGridView(context: Context, appContext: AppContext) : ExpoView(context, appContext), GridDataListener {
  private val onBenchmarkResult by EventDispatcher()
  private val onFirstFrame by EventDispatcher()

  // React Native doesn't lay out native children; let Android do it (needed for RecyclerView).
  override val shouldUseAndroidLayout = true

  private val density = context.resources.displayMetrics.density
  private val container = FrameLayout(context)
  private val overlay = PerfOverlayView(context)
  private val driver = BenchmarkDriver(density)
  private var renderer: GridRenderer? = null
  private var rendererId = "A"
  private var monitor: PerfMonitor? = null
  private var firstFrameSent = false
  private var lastBenchmarkRunId = 0
  private var settleUntil = 0L
  private var pendingBenchmark = false

  // Props in dp; geometry in px (rounded to whole pixels so all candidates agree).
  private var columnWidthDp = 88.0
  private var rowHeightDp = 32.0
  private var headerHeightDp = 36.0
  private var frozenColumns = 1
  private var data: GridData? = null

  var showOverlay = false
    set(value) {
      field = value
      overlay.visibility = if (value) VISIBLE else GONE
    }

  init {
    addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    overlay.visibility = GONE
    driver.onFinish = { finishBenchmark() }
    makeRenderer()
  }

  private fun px(dp: Double) = (dp * density).roundToInt().toDouble()

  private fun currentGeometry() = GridGeometry(
    rowCount = data?.rowCount ?: 0,
    columnCount = data?.columnCount ?: 0,
    columnWidth = px(columnWidthDp),
    rowHeight = px(rowHeightDp),
    headerHeight = px(headerHeightDp),
    frozenColumns = frozenColumns,
  )

  private fun applyGeometry() {
    renderer?.geometry = currentGeometry()
  }

  // ---- Props ----

  fun setData(newData: GridData?) {
    data?.removeListener(this)
    data = newData
    newData?.addListener(this)
    applyGeometry()
    renderer?.data = newData
  }

  fun setRenderer(id: String) {
    if (id == rendererId) return
    rendererId = id
    makeRenderer()
  }

  fun setColumnWidth(dp: Double) { columnWidthDp = dp; applyGeometry() }
  fun setRowHeight(dp: Double) { rowHeightDp = dp; applyGeometry() }
  fun setHeaderHeight(dp: Double) { headerHeightDp = dp; applyGeometry() }
  fun setFrozenColumns(n: Int) { frozenColumns = n; applyGeometry() }

  fun setBenchmarkRunId(id: Int) {
    if (id == lastBenchmarkRunId) return
    lastBenchmarkRunId = id
    if (id > 0) startBenchmark()
  }

  override fun onGridDataChanged(data: GridData) {
    applyGeometry()
    renderer?.reloadAll()
  }

  // ---- Renderer lifecycle ----

  private fun makeRenderer() {
    monitor?.dispose()
    container.removeAllViews()
    val r: GridRenderer = when (rendererId) {
      "B" -> RecyclerGridView(context)
      "C" -> ComposeGridView(context)
      else -> CanvasGridView(context)
    }
    r.geometry = currentGeometry()
    r.data = data
    container.addView(r.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val m = (8 * density).toInt()
    container.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(m, m, m, m) })
    overlay.rendererName = r.displayName
    renderer = r
    firstFrameSent = false

    val monitor = PerfMonitor(context)
    monitor.target = r
    monitor.onSample = { s ->
      if (showOverlay) overlay.update(s)
      val ttff = s.timeToFirstFrameMs
      if (!firstFrameSent && ttff != null) {
        firstFrameSent = true
        onFirstFrame(mapOf("renderer" to rendererId, "timeToFirstFrameMs" to ttff))
      }
    }
    monitor.onFrame = { now -> driveBenchmark(now) }
    if (isAttachedToWindow) monitor.start()
    this.monitor = monitor
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    monitor?.start()
  }

  override fun onDetachedFromWindow() {
    monitor?.stop()
    super.onDetachedFromWindow()
  }

  // ---- Benchmark ----

  private fun maxX() = currentGeometry().maxOffsetX(width.toDouble())
  private fun maxY() = currentGeometry().maxOffsetY(height.toDouble())

  private fun startBenchmark() {
    val r = renderer ?: return
    val g = currentGeometry()
    if (g.rowCount == 0) return
    driver.cancel()
    r.setOffset(0.0, minOf(g.rowY(g.rowCount / 2), maxY()))
    r.benchmarkRunning = true
    settleUntil = SystemClock.elapsedRealtimeNanos() + 500_000_000L
    pendingBenchmark = true
  }

  private fun driveBenchmark(frameTimeNanos: Long) {
    val r = renderer ?: return
    val m = monitor ?: return
    if (pendingBenchmark) {
      // Choreographer frame time uses System.nanoTime(), same clock base as elapsedRealtimeNanos minus sleep.
      if (SystemClock.elapsedRealtimeNanos() < settleUntil) return
      pendingBenchmark = false
      m.resetCounters()
      m.beginCollecting()
      driver.start(r.offsetX, r.offsetY, frameTimeNanos)
      return
    }
    // Aim for where content should be when this frame is displayed (one vsync ahead).
    val target = frameTimeNanos + (1e9 / m.refreshRate).toLong()
    val p = driver.offsetAt(target, maxX(), maxY()) ?: return
    r.setOffset(p[0], p[1])
  }

  private fun finishBenchmark() {
    val m = monitor ?: return
    val r = renderer
    r?.benchmarkRunning = false
    val s = m.endCollecting()
    val elapsedSec = s.elapsedNanos / 1e9
    val g = currentGeometry()
    onBenchmarkResult(
      mapOf(
        "platform" to "android",
        "renderer" to rendererId,
        "rendererName" to (r?.displayName ?: ""),
        "rows" to g.rowCount,
        "columns" to g.columnCount,
        "frames" to s.frames,
        "droppedFrames" to s.dropped,
        "durationMs" to elapsedSec * 1000,
        "avgFps" to if (elapsedSec > 0) s.frames / elapsedSec else 0.0,
        "refreshRate" to m.refreshRate,
        "worstFrameMs" to s.worstFrameMs,
        "blankAvgPct" to if (s.blankSamples > 0) s.blankSum / s.blankSamples * 100 else 0.0,
        "blankMaxPct" to s.blankMax * 100,
        "peakResidentMB" to maxOf(s.peakResident, PerfMonitor.readResidentBytes()) / 1048576.0,
        "timeToFirstFrameMs" to (m.timeToFirstFrameMs ?: -1.0),
      )
    )
  }
}

package expo.modules.datagrid.spike

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import expo.modules.datagrid.GridData
import expo.modules.datagrid.GridGeometry
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.ui.layout.Placeable
import kotlin.math.roundToInt

/**
 * Candidate C: Compose LazyLayout that composes only the visible cells.
 * Each cell is a Box + BasicText. Two `scrollable` modifiers give drag, fling and overscroll per axis.
 * Offsets are Doubles in snapshot state; reading them in the measure block re-runs measure/placement
 * only, not composition.
 */
@SuppressLint("ViewConstructor")
class ComposeGridView(context: Context) : FrameLayout(context), GridRenderer {
  override val view: View get() = this
  override val displayName = "Android C · Compose LazyLayout"
  override var benchmarkRunning = false

  private val style = GridStyle(context)
  private val x = mutableDoubleStateOf(0.0)
  private val y = mutableDoubleStateOf(0.0)
  private val version = mutableIntStateOf(0)
  private var geometryState = GridGeometry()
  private var horizontal: ScrollableState? = null
  private var vertical: ScrollableState? = null

  // What the last measure pass placed; used for the blank metric.
  private var placed = HashSet<Long>()
  private var placedX = 0.0
  private var placedY = 0.0

  override var geometry: GridGeometry
    get() = geometryState
    set(value) {
      if (geometryState == value) return
      geometryState = value
      clamp()
      version.intValue++
    }

  override var data: GridData? = null
    set(value) {
      field = value
      version.intValue++
    }

  private val composeView = ComposeView(context)

  init {
    composeView.setContent { Grid() }
    addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  override fun reloadAll() {
    version.intValue++
  }

  private fun maxX() = geometryState.maxOffsetX(width.toDouble())
  private fun maxY() = geometryState.maxOffsetY(height.toDouble())

  private fun clamp() {
    x.doubleValue = x.doubleValue.coerceIn(0.0, maxX())
    y.doubleValue = y.doubleValue.coerceIn(0.0, maxY())
  }

  override val offsetX: Double get() = x.doubleValue
  override val offsetY: Double get() = y.doubleValue

  override fun setOffset(x: Double, y: Double) {
    this.x.doubleValue = x.coerceIn(0.0, maxX())
    this.y.doubleValue = y.coerceIn(0.0, maxY())
  }

  private fun consume(state: MutableDoubleState, delta: Float, max: Double): Float {
    val old = state.doubleValue
    val new = (old + delta).coerceIn(0.0, max)
    state.doubleValue = new
    return (new - old).toFloat()
  }

  private fun indexOf(row: Int, col: Int) = (row + 1) * geometryState.columnCount + col

  @OptIn(ExperimentalFoundationApi::class)
  @Composable
  private fun Grid() {
    // Read the version so data/geometry changes recompose the item provider.
    val v = version.intValue
    val hState = remember { ScrollableState { d -> consume(x, d, maxX()) } }
    val vState = remember { ScrollableState { d -> consume(y, d, maxY()) } }
    horizontal = hState
    vertical = vState
    val overscroll = rememberOverscrollEffect()
    val provider = remember(v) { CellProvider() }

    LazyLayout(
      itemProvider = { provider },
      modifier = Modifier
        .fillMaxSize()
        .clipToBounds()
        .background(Color(style.background))
        .overscroll(overscroll)
        // reverseDirection = true: dragging down moves content down, i.e. offset decreases.
        .scrollable(vState, Orientation.Vertical, overscroll, reverseDirection = true)
        .scrollable(hState, Orientation.Horizontal, overscroll, reverseDirection = true),
    ) { constraints ->
      val g = geometryState
      val w = constraints.maxWidth
      val h = constraints.maxHeight
      val ox = x.doubleValue
      val oy = y.doubleValue
      val rows = g.visibleRows(oy, h.toDouble())
      val cols = g.visibleBodyColumns(ox, w.toDouble())
      val cw = g.columnWidth.roundToInt()
      val rh = g.rowHeight.roundToInt()
      val hh = g.headerHeight.roundToInt()

      class P(val p: Placeable, val x: Int, val y: Int, val z: Float)
      val items = ArrayList<P>((rows.count() + 1) * (cols.count() + g.frozenCount))
      val keys = HashSet<Long>(items.size * 2 + 16)

      fun add(row: Int, col: Int, z: Float) {
        val ch = if (row < 0) hh else rh
        val measurables = compose(indexOf(row, col))
        val px = g.screenX(col, ox).roundToInt()
        val py = g.screenY(row, oy).roundToInt()
        for (m in measurables) items.add(P(m.measure(Constraints.fixed(cw, ch)), px, py, z))
        keys.add(GridGeometry.key(row, col))
      }

      if (g.rowCount > 0) {
        for (r in rows) for (c in cols) add(r, c, 0f)
        for (r in rows) for (c in 0 until g.frozenCount) add(r, c, 1f)
        for (c in cols) add(-1, c, 2f)
        for (c in 0 until g.frozenCount) add(-1, c, 3f)
      }
      placed = keys
      placedX = ox
      placedY = oy

      layout(w, h) {
        for (item in items) item.p.place(item.x, item.y, item.z)
      }
    }
  }

  private inner class CellProvider : LazyLayoutItemProvider {
    private val g = geometryState
    private val d = data
    private val bodyText = TextStyle(color = Color(style.textColor), fontSize = 13.sp, textAlign = TextAlign.End, fontFeatureSettings = "tnum")
    private val negativeText = bodyText.copy(color = Color(style.negativeColor))
    private val headerText = TextStyle(color = Color(style.textColor), fontSize = 13.sp, fontWeight = FontWeight.Bold)

    override val itemCount: Int get() = (g.rowCount + 1) * g.columnCount

    override fun getContentType(index: Int): Any = if (index < g.columnCount) 1 else 0

    @Composable
    override fun Item(index: Int, key: Any) {
      val row = index / g.columnCount - 1
      val col = index % g.columnCount
      val bg = Color(style.backgroundColor(row, col, g.frozenCount))
      Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        if (row < 0) {
          BasicText(GridStyle.headerTitle(col), style = headerText, maxLines = 1)
        } else {
          val text = d?.text(row, col) ?: ""
          val negative = (d?.number(row, col) ?: 0.0) < 0
          BasicText(text, modifier = Modifier.fillMaxWidth(), style = if (negative) negativeText else bodyText, maxLines = 1)
        }
      }
    }
  }

  // ---- PerfMonitorTarget ----

  override val isMoving: Boolean
    get() = benchmarkRunning || horizontal?.isScrollInProgress == true || vertical?.isScrollInProgress == true

  override val hasDrawnContent: Boolean get() = placed.isNotEmpty()

  /**
   * Blank area of the last placed frame. LazyLayout composes every visible cell synchronously in
   * measure, so this is 0 by construction; slow frames show up as dropped frames instead.
   */
  override fun currentBlankFraction(): Double {
    val keys = placed
    return geometryState.blankFraction(placedX, placedY, width.toDouble(), height.toDouble()) { r, c -> GridGeometry.key(r, c) in keys }
  }
}

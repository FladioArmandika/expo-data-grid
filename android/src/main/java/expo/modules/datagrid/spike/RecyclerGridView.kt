package expo.modules.datagrid.spike

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import expo.modules.datagrid.FastNumberFormat
import expo.modules.datagrid.GridData
import expo.modules.datagrid.GridGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Candidate B: two vertically synced RecyclerViews (the approach Qlik's grid uses).
 * Left: frozen columns. Right: body rows (one TextView per cell) inside a HorizontalScrollView.
 * The header row is a plain LinearLayout scrolled in step with the HorizontalScrollView.
 * Vertical sync is done by forwarding scroll deltas from one RecyclerView to the other.
 */
@SuppressLint("ViewConstructor")
class RecyclerGridView(context: Context) : FrameLayout(context), GridRenderer {
  override val view: View get() = this
  override val displayName = "Android B · 2 synced RecyclerViews"
  override var benchmarkRunning = false

  private val style = GridStyle(context)
  private val frozenList = RecyclerView(context)
  private val bodyList = RecyclerView(context)
  private val bodyScroll = HorizontalScrollView(context)
  private val headerClip = FrameLayout(context)
  private val headerRow = LinearLayout(context)
  private val cornerRow = LinearLayout(context)
  private val frozenLayout = LinearLayoutManager(context)
  private val bodyLayout = LinearLayoutManager(context)
  private var syncing = false
  private var scrollState = RecyclerView.SCROLL_STATE_IDLE

  override var geometry = GridGeometry()
    set(value) {
      if (field == value) return
      field = value
      rebuild()
    }

  override var data: GridData? = null
    set(value) {
      field = value
      reloadAll()
    }

  init {
    setBackgroundColor(style.background)
    frozenList.layoutManager = frozenLayout
    bodyList.layoutManager = bodyLayout
    frozenList.itemAnimator = null
    bodyList.itemAnimator = null
    frozenList.isVerticalScrollBarEnabled = false
    bodyScroll.isHorizontalScrollBarEnabled = true
    bodyScroll.addView(bodyList)
    headerClip.addView(headerRow)
    headerClip.clipChildren = true

    addView(bodyScroll)
    addView(frozenList)
    addView(headerClip)
    addView(cornerRow)

    val forward = { from: RecyclerView, to: RecyclerView ->
      object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
          if (syncing || dy == 0) return
          syncing = true
          to.scrollBy(0, dy)
          syncing = false
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
          scrollState = newState
          // Stop the other list's fling when this one is touched, so they don't fight.
          if (newState == RecyclerView.SCROLL_STATE_DRAGGING) to.stopScroll()
        }
      }
    }
    bodyList.addOnScrollListener(forward(bodyList, frozenList))
    frozenList.addOnScrollListener(forward(frozenList, bodyList))
    bodyScroll.setOnScrollChangeListener { _, x, _, _, _ -> headerRow.translationX = -x.toFloat() }
  }

  private fun cellView(width: Int, height: Int, header: Boolean): TextView = TextView(context).apply {
    layoutParams = LinearLayout.LayoutParams(width, height)
    gravity = (if (header) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
    setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSizePx)
    setTextColor(style.textColor)
    val pad = style.paddingPx.roundToInt()
    setPadding(pad, 0, pad, 0)
    maxLines = 1
    includeFontPadding = false
    if (header) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else fontFeatureSettings = "tnum"
  }

  private fun rebuild() {
    val g = geometry
    val cw = g.columnWidth.roundToInt()
    val rh = g.rowHeight.roundToInt()
    val hh = g.headerHeight.roundToInt()
    val fw = g.frozenWidth.roundToInt()
    val bodyWidth = (g.contentWidth - g.frozenWidth).roundToInt()

    cornerRow.removeAllViews()
    headerRow.removeAllViews()
    for (c in 0 until g.frozenCount) cornerRow.addView(cellView(cw, hh, true).apply { text = GridStyle.headerTitle(c); setBackgroundColor(style.headerBackground) })
    for (c in g.frozenCount until g.columnCount) headerRow.addView(cellView(cw, hh, true).apply { text = GridStyle.headerTitle(c); setBackgroundColor(style.headerBackground) })

    cornerRow.layoutParams = LayoutParams(fw, hh)
    headerClip.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, hh).apply { leftMargin = fw }
    headerRow.layoutParams = LayoutParams(bodyWidth, hh)
    frozenList.layoutParams = LayoutParams(fw, LayoutParams.MATCH_PARENT).apply { topMargin = hh }
    bodyScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = hh; leftMargin = fw }
    bodyList.layoutParams = FrameLayout.LayoutParams(bodyWidth, LayoutParams.MATCH_PARENT)

    frozenList.adapter = RowAdapter(0 until g.frozenCount, cw, rh)
    bodyList.adapter = RowAdapter(g.frozenCount until g.columnCount, cw, rh)
    // Each row is a different set of TextViews per list; keep a decent pool for fast flings.
    bodyList.setItemViewCacheSize(4)
    bodyList.recycledViewPool.setMaxRecycledViews(0, 24)
  }

  @SuppressLint("NotifyDataSetChanged")
  override fun reloadAll() {
    frozenList.adapter?.notifyDataSetChanged()
    bodyList.adapter?.notifyDataSetChanged()
  }

  private class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
    var boundRow = -1
    // TextView.setText(char[], ...) keeps a reference to the array, so each cell needs its own.
    val chars = Array(row.childCount) { CharArray(FastNumberFormat.BUFFER_SIZE) }
  }

  private inner class RowAdapter(val columns: IntRange, val cellWidth: Int, val rowHeight: Int) : RecyclerView.Adapter<RowHolder>() {
    override fun getItemCount(): Int = geometry.rowCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
      val row = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = RecyclerView.LayoutParams(cellWidth * (columns.last - columns.first + 1), rowHeight)
      }
      for (c in columns) row.addView(cellView(cellWidth, rowHeight, false))
      return RowHolder(row)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
      holder.boundRow = position
      val frozenCount = geometry.frozenCount
      val d = data
      holder.row.setBackgroundColor(style.backgroundColor(position, columns.first, frozenCount))
      var i = 0
      for (c in columns) {
        val chars = holder.chars[i]
        val tv = holder.row.getChildAt(i++) as TextView
        if (d == null) {
          tv.text = ""
          continue
        }
        val start = d.formatInto(position, c, chars)
        tv.setText(chars, start, chars.size - start)
        tv.setTextColor(if (d.number(position, c) < 0) style.negativeColor else style.textColor)
      }
    }
  }

  // ---- Offsets ----

  private fun listOffset(lm: LinearLayoutManager): Double {
    val first = lm.findFirstVisibleItemPosition()
    if (first == RecyclerView.NO_POSITION) return 0.0
    val v = lm.findViewByPosition(first) ?: return 0.0
    return first * geometry.rowHeight - v.top
  }

  override val offsetX: Double get() = bodyScroll.scrollX.toDouble()
  override val offsetY: Double get() = listOffset(bodyLayout)

  override fun setOffset(x: Double, y: Double) {
    bodyScroll.scrollTo(x.roundToInt(), 0)
    val target = y.coerceIn(0.0, geometry.maxOffsetY(height.toDouble()))
    val delta = target - offsetY
    if (abs(delta) > height * 2) {
      val row = (target / geometry.rowHeight).toInt()
      val within = -(target - row * geometry.rowHeight).roundToInt()
      bodyLayout.scrollToPositionWithOffset(row, within)
      frozenLayout.scrollToPositionWithOffset(row, within)
    } else if (delta != 0.0) {
      bodyList.scrollBy(0, delta.roundToInt())
    }
  }

  // ---- PerfMonitorTarget ----

  override val isMoving: Boolean
    get() = benchmarkRunning || scrollState != RecyclerView.SCROLL_STATE_IDLE

  override val hasDrawnContent: Boolean get() = bodyList.childCount > 0

  override fun currentBlankFraction(): Double {
    val bodyRows = HashSet<Int>()
    val frozenRows = HashSet<Int>()
    for (i in 0 until bodyList.childCount) {
      val h = bodyList.getChildViewHolder(bodyList.getChildAt(i)) as RowHolder
      if (h.boundRow >= 0 && !h.row.isLayoutRequested) bodyRows.add(h.boundRow)
    }
    for (i in 0 until frozenList.childCount) {
      val h = frozenList.getChildViewHolder(frozenList.getChildAt(i)) as RowHolder
      if (h.boundRow >= 0 && !h.row.isLayoutRequested) frozenRows.add(h.boundRow)
    }
    // If the two lists drift apart, frozen cells are on screen but in the wrong place: count them as blank.
    val aligned = abs(listOffset(frozenLayout) - listOffset(bodyLayout)) < 1.0
    val g = geometry
    return g.blankFraction(offsetX, offsetY, width.toDouble(), height.toDouble()) { row, col ->
      when {
        row < 0 -> true
        col < g.frozenCount -> aligned && row in frozenRows
        else -> row in bodyRows
      }
    }
  }
}

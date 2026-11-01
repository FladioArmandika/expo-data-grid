package expo.modules.datagrid.spike

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.EdgeEffect
import android.widget.OverScroller
import expo.modules.datagrid.FastNumberFormat
import expo.modules.datagrid.GridData
import expo.modules.datagrid.GridGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Candidate A: one View that draws every visible cell on a Canvas in onDraw.
 * Scrolling: own touch handling, VelocityTracker + OverScroller fling, EdgeEffect on all four sides.
 * Offsets are Doubles; everything handed to Canvas is viewport-relative, so precision holds at row 1M.
 */
@SuppressLint("ViewConstructor")
class CanvasGridView(context: Context) : View(context), GridRenderer {
  override val view: View get() = this
  override val displayName = "Android A · Canvas View"
  override var benchmarkRunning = false

  private val style = GridStyle(context)
  private val textPaint = style.textPaint()
  private val negativePaint = style.textPaint().also { it.color = style.negativeColor }
  private val headerPaint = style.headerPaint()
  private val fillPaint = Paint()
  private val chars = CharArray(FastNumberFormat.BUFFER_SIZE)
  private val baselineOffset: Float

  private val scroller = OverScroller(context)
  private var velocityTracker: VelocityTracker? = null
  private val touchSlop: Int
  private val minFling: Int
  private val maxFling: Int
  private val edgeTop = EdgeEffect(context)
  private val edgeBottom = EdgeEffect(context)
  private val edgeLeft = EdgeEffect(context)
  private val edgeRight = EdgeEffect(context)

  override var offsetX = 0.0
    private set
  override var offsetY = 0.0
    private set

  private var dragging = false
  private var lockAxis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical, 3 = free
  private var lastX = 0f
  private var lastY = 0f
  private var downX = 0f
  private var downY = 0f
  private var drewBody = false

  override var geometry = GridGeometry()
    set(value) {
      if (field == value) return
      field = value
      clampOffsets()
      invalidate()
    }

  override var data: GridData? = null
    set(value) {
      field = value
      invalidate()
    }

  init {
    val vc = ViewConfiguration.get(context)
    touchSlop = vc.scaledTouchSlop
    minFling = vc.scaledMinimumFlingVelocity
    maxFling = vc.scaledMaximumFlingVelocity
    val fm = textPaint.fontMetrics
    baselineOffset = -(fm.ascent + fm.descent) / 2f
    setWillNotDraw(false)
    isHorizontalScrollBarEnabled = true
    isVerticalScrollBarEnabled = true
  }

  override fun reloadAll() = invalidate()

  // ---- Scrolling ----

  private val maxX get() = geometry.maxOffsetX(width.toDouble())
  private val maxY get() = geometry.maxOffsetY(height.toDouble())

  private fun clampOffsets() {
    offsetX = offsetX.coerceIn(0.0, maxX)
    offsetY = offsetY.coerceIn(0.0, maxY)
  }

  override fun setOffset(x: Double, y: Double) {
    offsetX = x.coerceIn(0.0, maxX)
    offsetY = y.coerceIn(0.0, maxY)
    if (!scroller.isFinished) scroller.abortAnimation()
    postInvalidateOnAnimation()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    clampOffsets()
    edgeTop.setSize(w, h)
    edgeBottom.setSize(w, h)
    edgeLeft.setSize(h, w)
    edgeRight.setSize(h, w)
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    val vt = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
    vt.addMovement(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (!scroller.isFinished) {
          scroller.abortAnimation()
          dragging = true
        }
        lastX = event.x; lastY = event.y
        downX = event.x; downY = event.y
        lockAxis = 0
        parent?.requestDisallowInterceptTouchEvent(true)
      }
      MotionEvent.ACTION_MOVE -> {
        var dx = lastX - event.x
        var dy = lastY - event.y
        if (!dragging) {
          val tx = abs(event.x - downX)
          val ty = abs(event.y - downY)
          if (tx > touchSlop || ty > touchSlop) {
            dragging = true
            lockAxis = when {
              tx > ty * 2 -> 1
              ty > tx * 2 -> 2
              else -> 3
            }
          }
        }
        if (dragging) {
          if (lockAxis == 1) dy = 0f
          if (lockAxis == 2) dx = 0f
          scrollByWithEdges(dx.toDouble(), dy.toDouble(), event.x, event.y)
          lastX = event.x; lastY = event.y
        }
      }
      MotionEvent.ACTION_UP -> {
        if (dragging) {
          vt.computeCurrentVelocity(1000, maxFling.toFloat())
          var vx = -vt.xVelocity
          var vy = -vt.yVelocity
          if (lockAxis == 1) vy = 0f
          if (lockAxis == 2) vx = 0f
          if (abs(vx) > minFling || abs(vy) > minFling) fling(vx.toInt(), vy.toInt())
        }
        endDrag()
      }
      MotionEvent.ACTION_CANCEL -> endDrag()
    }
    return true
  }

  private fun endDrag() {
    dragging = false
    velocityTracker?.recycle()
    velocityTracker = null
    edgeTop.onRelease(); edgeBottom.onRelease(); edgeLeft.onRelease(); edgeRight.onRelease()
    postInvalidateOnAnimation()
  }

  private fun scrollByWithEdges(dx: Double, dy: Double, x: Float, y: Float) {
    val nx = offsetX + dx
    val ny = offsetY + dy
    if (nx < 0) edgeLeft.onPull((-nx / width).toFloat(), 1f - y / height)
    else if (nx > maxX) edgeRight.onPull(((nx - maxX) / width).toFloat(), y / height)
    if (ny < 0) edgeTop.onPull((-ny / height).toFloat(), x / width)
    else if (ny > maxY) edgeBottom.onPull(((ny - maxY) / height).toFloat(), 1f - x / width)
    offsetX = nx.coerceIn(0.0, maxX)
    offsetY = ny.coerceIn(0.0, maxY)
    awakenScrollBars()
    postInvalidateOnAnimation()
  }

  private fun fling(vx: Int, vy: Int) {
    // OverScroller works in Int pixels; 1M rows at 32dp is ~88M px, well inside Int range.
    scroller.fling(
      offsetX.roundToInt(), offsetY.roundToInt(), vx, vy,
      0, maxX.toInt(), 0, maxY.toInt()
    )
    postInvalidateOnAnimation()
  }

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      val nx = scroller.currX.toDouble()
      val ny = scroller.currY.toDouble()
      val v = scroller.currVelocity.toInt()
      if (nx <= 0 && offsetX > 0) edgeLeft.onAbsorb(v)
      if (nx >= maxX && offsetX < maxX) edgeRight.onAbsorb(v)
      if (ny <= 0 && offsetY > 0) edgeTop.onAbsorb(v)
      if (ny >= maxY && offsetY < maxY) edgeBottom.onAbsorb(v)
      offsetX = nx.coerceIn(0.0, maxX)
      offsetY = ny.coerceIn(0.0, maxY)
      postInvalidateOnAnimation()
    }
  }

  // Scrollbars: View draws them from these.
  override fun computeVerticalScrollRange(): Int = (geometry.contentHeight / 64).toInt()
  override fun computeVerticalScrollOffset(): Int = (offsetY / 64).toInt()
  override fun computeVerticalScrollExtent(): Int = height / 64
  override fun computeHorizontalScrollRange(): Int = geometry.contentWidth.toInt()
  override fun computeHorizontalScrollOffset(): Int = offsetX.toInt()
  override fun computeHorizontalScrollExtent(): Int = width

  // ---- Drawing ----

  override fun onDraw(canvas: Canvas) {
    val g = geometry
    canvas.drawColor(style.background)
    if (g.rowCount == 0 || g.columnCount == 0) return
    val w = width.toDouble()
    val h = height.toDouble()
    val rows = g.visibleRows(offsetY, h)
    val cols = g.visibleBodyColumns(offsetX, w)
    val fw = g.frozenWidth.toFloat()
    val hh = g.headerHeight.toFloat()

    // Body
    canvas.save()
    canvas.clipRect(fw, hh, width.toFloat(), height.toFloat())
    for (r in rows) for (c in cols) drawCell(canvas, r, c)
    canvas.restore()

    // Frozen columns
    canvas.save()
    canvas.clipRect(0f, hh, fw, height.toFloat())
    for (r in rows) for (c in 0 until g.frozenCount) drawCell(canvas, r, c)
    canvas.restore()

    // Header (scrolls horizontally) and corner
    canvas.save()
    canvas.clipRect(fw, 0f, width.toFloat(), hh)
    for (c in cols) drawCell(canvas, -1, c)
    canvas.restore()
    for (c in 0 until g.frozenCount) drawCell(canvas, -1, c)

    drewBody = !rows.isEmpty()
    drawEdges(canvas)
  }

  private fun drawCell(canvas: Canvas, row: Int, col: Int) {
    val g = geometry
    val left = g.screenX(col, offsetX).toFloat()
    val top = g.screenY(row, offsetY).toFloat()
    val cw = g.columnWidth.toFloat()
    val ch = (if (row < 0) g.headerHeight else g.rowHeight).toFloat()
    fillPaint.color = style.backgroundColor(row, col, g.frozenCount)
    canvas.drawRect(left, top, left + cw, top + ch, fillPaint)

    val cy = top + ch / 2f + baselineOffset
    if (row < 0) {
      canvas.drawText(GridStyle.headerTitle(col), left + style.paddingPx, cy, headerPaint)
      return
    }
    val d = data ?: return
    val start = d.formatInto(row, col, chars)
    val len = chars.size - start
    if (len == 0) return
    val paint = if (d.number(row, col) < 0) negativePaint else textPaint
    val tw = paint.measureText(chars, start, len)
    canvas.drawText(chars, start, len, left + cw - style.paddingPx - tw, cy, paint)
  }

  private fun drawEdges(canvas: Canvas) {
    var more = false
    val hh = geometry.headerHeight.toFloat()
    if (!edgeTop.isFinished) {
      val s = canvas.save()
      canvas.translate(0f, hh)
      more = edgeTop.draw(canvas) or more
      canvas.restoreToCount(s)
    }
    if (!edgeBottom.isFinished) {
      val s = canvas.save()
      canvas.rotate(180f)
      canvas.translate(-width.toFloat(), -height.toFloat())
      more = edgeBottom.draw(canvas) or more
      canvas.restoreToCount(s)
    }
    if (!edgeLeft.isFinished) {
      val s = canvas.save()
      canvas.rotate(270f)
      canvas.translate(-height.toFloat(), 0f)
      more = edgeLeft.draw(canvas) or more
      canvas.restoreToCount(s)
    }
    if (!edgeRight.isFinished) {
      val s = canvas.save()
      canvas.rotate(90f)
      canvas.translate(0f, -width.toFloat())
      more = edgeRight.draw(canvas) or more
      canvas.restoreToCount(s)
    }
    if (more) postInvalidateOnAnimation()
  }

  // ---- PerfMonitorTarget ----

  override val isMoving: Boolean get() = benchmarkRunning || dragging || !scroller.isFinished

  override val hasDrawnContent: Boolean get() = drewBody

  /**
   * Every visible cell is drawn in each onDraw, so blank area is 0 by construction.
   * A late frame shows stale pixels, not empty ones; that shows up as dropped frames instead.
   */
  override fun currentBlankFraction(): Double = 0.0
}

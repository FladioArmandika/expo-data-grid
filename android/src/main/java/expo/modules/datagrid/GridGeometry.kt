package expo.modules.datagrid

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Layout math shared by every renderer, in pixels. Content coordinates use Double because
 * 1M rows x 32dp is ~88M px at 2.75x density, past the point where Float can address single pixels.
 * Header occupies y in [0, headerHeight); body row r starts at headerHeight + r * rowHeight.
 */
data class GridGeometry(
  val rowCount: Int = 0,
  val columnCount: Int = 0,
  val columnWidth: Double = 88.0,
  val rowHeight: Double = 32.0,
  val headerHeight: Double = 36.0,
  val frozenColumns: Int = 1,
) {
  val frozenCount: Int get() = min(max(frozenColumns, 0), columnCount)
  val frozenWidth: Double get() = frozenCount * columnWidth
  val contentWidth: Double get() = columnCount * columnWidth
  val contentHeight: Double get() = headerHeight + rowCount * rowHeight

  fun columnX(col: Int): Double = col * columnWidth
  fun rowY(row: Int): Double = headerHeight + row * rowHeight

  fun rows(minY: Double, maxY: Double): IntRange {
    if (rowCount <= 0 || maxY <= minY) return IntRange.EMPTY
    val first = floor((minY - headerHeight) / rowHeight).toLong().coerceIn(0, rowCount.toLong()).toInt()
    val last = ceil((maxY - headerHeight) / rowHeight).toLong().coerceIn(0, rowCount.toLong()).toInt()
    return if (last > first) first until last else IntRange.EMPTY
  }

  fun bodyColumns(minX: Double, maxX: Double): IntRange {
    if (columnCount <= frozenCount || maxX <= minX) return IntRange.EMPTY
    val first = floor(minX / columnWidth).toInt().coerceIn(frozenCount, columnCount)
    val last = ceil(maxX / columnWidth).toInt().coerceIn(frozenCount, columnCount)
    return if (last > first) first until last else IntRange.EMPTY
  }

  fun visibleRows(offsetY: Double, height: Double): IntRange = rows(offsetY + headerHeight, offsetY + height)

  fun visibleBodyColumns(offsetX: Double, width: Double): IntRange = bodyColumns(offsetX + frozenWidth, offsetX + width)

  fun maxOffsetX(viewportWidth: Double): Double = max(0.0, contentWidth - viewportWidth)
  fun maxOffsetY(viewportHeight: Double): Double = max(0.0, contentHeight - viewportHeight)

  /** Screen-space left edge of a column. */
  fun screenX(col: Int, offsetX: Double): Double = if (col < frozenCount) columnX(col) else columnX(col) - offsetX

  /** Screen-space top edge of a row; row == -1 is the header. */
  fun screenY(row: Int, offsetY: Double): Double = if (row < 0) 0.0 else rowY(row) - offsetY

  /**
   * Fraction of the viewport that should show a cell but doesn't:
   * (visible cell area not drawn) / (viewport area covered by content). All renderers use this.
   */
  fun blankFraction(offsetX: Double, offsetY: Double, width: Double, height: Double, isDrawn: (row: Int, col: Int) -> Boolean): Double {
    val w = min(width, contentWidth)
    val h = min(height, contentHeight)
    if (w <= 0 || h <= 0) return 0.0
    val fw = min(frozenWidth, w)
    val hh = min(headerHeight, h)
    var missing = 0.0

    fun add(row: Int, col: Int, clipLeft: Double, clipTop: Double, clipRight: Double, clipBottom: Double) {
      if (isDrawn(row, col)) return
      val x0 = max(screenX(col, offsetX), clipLeft)
      val y0 = max(screenY(row, offsetY), clipTop)
      val x1 = min(screenX(col, offsetX) + columnWidth, clipRight)
      val y1 = min(screenY(row, offsetY) + (if (row < 0) headerHeight else rowHeight), clipBottom)
      if (x1 > x0 && y1 > y0) missing += (x1 - x0) * (y1 - y0)
    }

    val rows = visibleRows(offsetY, height)
    val cols = visibleBodyColumns(offsetX, width)
    for (c in 0 until frozenCount) add(-1, c, 0.0, 0.0, fw, hh)
    for (c in cols) add(-1, c, fw, 0.0, w, hh)
    for (r in rows) {
      for (c in 0 until frozenCount) add(r, c, 0.0, hh, fw, h)
      for (c in cols) add(r, c, fw, hh, w, h)
    }
    return missing / (w * h)
  }

  companion object {
    fun key(row: Int, col: Int): Long = ((row + 1).toLong() shl 16) or (col.toLong() and 0xFFFF)
  }
}

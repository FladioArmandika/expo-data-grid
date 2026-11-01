package expo.modules.datagrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridGeometryTest {
  private val g = GridGeometry(rowCount = 1_000_000, columnCount = 50, columnWidth = 100.0, rowHeight = 30.0, headerHeight = 40.0, frozenColumns = 1)

  @Test fun contentSize() {
    assertEquals(5000.0, g.contentWidth, 0.0)
    assertEquals(40.0 + 30_000_000.0, g.contentHeight, 0.0)
  }

  @Test fun visibleRowsAtTop() {
    // Viewport 300 high: header 40, body 260 -> rows 0..8 (row 8 partially).
    assertEquals(0 until 9, g.visibleRows(0.0, 300.0))
  }

  @Test fun visibleRowsMidScroll() {
    // Offset 45 -> body top in content coords is 85 -> row 1 starts at 70, so row 1 is first.
    assertEquals(1, g.visibleRows(45.0, 300.0).first)
  }

  @Test fun visibleRowsAtEnd() {
    val maxY = g.maxOffsetY(300.0)
    val rows = g.visibleRows(maxY, 300.0)
    assertEquals(999_999, rows.last)
  }

  @Test fun visibleBodyColumnsSkipFrozen() {
    val cols = g.visibleBodyColumns(0.0, 350.0)
    assertEquals(1, cols.first)
    assertEquals(3, cols.last)
    val scrolled = g.visibleBodyColumns(150.0, 350.0)
    // body x range [250, 500) -> columns 2..4
    assertEquals(2 until 5, scrolled)
  }

  @Test fun noFrozenColumns() {
    val g0 = g.copy(frozenColumns = 0)
    assertEquals(0 until 4, g0.visibleBodyColumns(0.0, 350.0))
  }

  @Test fun blankIsZeroWhenEverythingDrawn() {
    assertEquals(0.0, g.blankFraction(123.0, 4567.0, 390.0, 800.0) { _, _ -> true }, 1e-12)
  }

  @Test fun blankIsOneWhenNothingDrawn() {
    assertEquals(1.0, g.blankFraction(123.0, 4567.0, 390.0, 800.0) { _, _ -> false }, 1e-9)
  }

  @Test fun blankCountsOnlyMissingCells() {
    // Only the header missing: 40 * 390 of 390 * 800.
    val f = g.blankFraction(0.0, 0.0, 390.0, 800.0) { row, _ -> row >= 0 }
    assertEquals(40.0 / 800.0, f, 1e-9)
  }

  @Test fun precisionAtLastRow() {
    // Screen positions stay exact at the bottom of 1M rows.
    val maxY = g.maxOffsetY(800.0)
    val y = g.screenY(999_999, maxY)
    assertEquals(800.0 - 30.0, y, 1e-6)
  }

  @Test fun keysAreUnique() {
    val keys = HashSet<Long>()
    for (r in -1 until 100) for (c in 0 until 50) assertTrue(keys.add(GridGeometry.key(r, c)))
  }
}

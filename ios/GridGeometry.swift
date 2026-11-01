import CoreGraphics

/// Layout math shared by every renderer. Content coordinates: the header occupies
/// y in [0, headerHeight), body row r occupies [headerHeight + r * rowHeight, ... + rowHeight).
/// Frozen columns are the first `frozenColumns` columns.
struct GridGeometry: Equatable {
  var rowCount: Int = 0
  var columnCount: Int = 0
  var columnWidth: CGFloat = 88
  var rowHeight: CGFloat = 32
  var headerHeight: CGFloat = 36
  var frozenColumns: Int = 1

  var frozenCount: Int { min(max(frozenColumns, 0), columnCount) }
  var frozenWidth: CGFloat { CGFloat(frozenCount) * columnWidth }
  var contentWidth: CGFloat { CGFloat(columnCount) * columnWidth }
  var contentHeight: CGFloat { headerHeight + CGFloat(rowCount) * rowHeight }

  func columnX(_ col: Int) -> CGFloat { CGFloat(col) * columnWidth }
  func rowY(_ row: Int) -> CGFloat { headerHeight + CGFloat(row) * rowHeight }

  /// Body rows intersecting the vertical band [minY, maxY) given in content coordinates.
  func rows(minY: CGFloat, maxY: CGFloat) -> Range<Int> {
    guard rowCount > 0, maxY > minY else { return 0..<0 }
    let first = Int(floor((minY - headerHeight) / rowHeight))
    let last = Int(ceil((maxY - headerHeight) / rowHeight))
    let lo = min(max(first, 0), rowCount)
    let hi = min(max(last, 0), rowCount)
    return lo..<max(lo, hi)
  }

  /// Non-frozen columns intersecting [minX, maxX) in content coordinates.
  func bodyColumns(minX: CGFloat, maxX: CGFloat) -> Range<Int> {
    guard columnCount > frozenCount, maxX > minX else { return frozenCount..<frozenCount }
    let first = Int(floor(minX / columnWidth))
    let last = Int(ceil(maxX / columnWidth))
    let lo = min(max(first, frozenCount), columnCount)
    let hi = min(max(last, frozenCount), columnCount)
    return lo..<max(lo, hi)
  }

  /// Rows visible on screen for a viewport of `height` scrolled to `offsetY`.
  func visibleRows(offsetY: CGFloat, height: CGFloat) -> Range<Int> {
    rows(minY: offsetY + headerHeight, maxY: offsetY + height)
  }

  /// Non-frozen columns visible on screen for a viewport of `width` scrolled to `offsetX`.
  func visibleBodyColumns(offsetX: CGFloat, width: CGFloat) -> Range<Int> {
    bodyColumns(minX: offsetX + frozenWidth, maxX: offsetX + width)
  }

  /// Screen-space rect of a cell. `row == -1` is the header.
  func screenRect(row: Int, col: Int, offset: CGPoint) -> CGRect {
    let x = col < frozenCount ? columnX(col) : columnX(col) - offset.x
    let y = row < 0 ? 0 : rowY(row) - offset.y
    return CGRect(x: x, y: y, width: columnWidth, height: row < 0 ? headerHeight : rowHeight)
  }

  /// Fraction of the viewport that should show a cell but doesn't.
  /// This is the "blank area" metric: (visible cell area not yet drawn) / (viewport area covered by content).
  /// Every renderer reports through this function so the numbers are comparable.
  func blankFraction(offset: CGPoint, viewport: CGSize, isDrawn: (_ row: Int, _ col: Int) -> Bool) -> Double {
    let w = min(viewport.width, contentWidth)
    let h = min(viewport.height, contentHeight)
    guard w > 0, h > 0 else { return 0 }
    let viewportRect = CGRect(x: 0, y: 0, width: w, height: h)
    let bodyClip = CGRect(x: frozenWidth, y: headerHeight, width: max(0, w - frozenWidth), height: max(0, h - headerHeight))
    let headerClip = CGRect(x: frozenWidth, y: 0, width: max(0, w - frozenWidth), height: headerHeight)
    let frozenClip = CGRect(x: 0, y: headerHeight, width: frozenWidth, height: max(0, h - headerHeight))
    let cornerClip = CGRect(x: 0, y: 0, width: frozenWidth, height: headerHeight)

    let rows = visibleRows(offsetY: offset.y, height: viewport.height)
    let cols = visibleBodyColumns(offsetX: offset.x, width: viewport.width)
    var missing: CGFloat = 0

    func add(_ row: Int, _ col: Int, _ clip: CGRect) {
      if isDrawn(row, col) { return }
      let r = screenRect(row: row, col: col, offset: offset).intersection(clip).intersection(viewportRect)
      if !r.isNull { missing += r.width * r.height }
    }

    for c in 0..<frozenCount { add(-1, c, cornerClip) }
    for c in cols { add(-1, c, headerClip) }
    for r in rows {
      for c in 0..<frozenCount { add(r, c, frozenClip) }
      for c in cols { add(r, c, bodyClip) }
    }
    return Double(missing / (w * h))
  }

  static func key(row: Int, col: Int) -> Int64 {
    (Int64(row + 1) << 16) | Int64(col & 0xFFFF)
  }
}

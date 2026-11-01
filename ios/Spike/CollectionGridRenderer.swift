import UIKit

// Candidate A: UICollectionView + custom layout that pins the header row and the frozen columns.
// Items: one section, item = row * columnCount + col. Header cells are supplementary views.

private let headerKind = "GridHeader"

final class FrozenGridLayout: UICollectionViewLayout {
  var geometry = GridGeometry()

  override var collectionViewContentSize: CGSize {
    CGSize(width: geometry.contentWidth, height: geometry.contentHeight)
  }

  override func shouldInvalidateLayout(forBoundsChange newBounds: CGRect) -> Bool {
    // Pinned elements move with every bounds change. Everything is computed on demand,
    // so a full invalidation only costs the visible attributes.
    true
  }

  private var pin: CGPoint {
    guard let cv = collectionView else { return .zero }
    return CGPoint(x: max(0, cv.bounds.minX), y: max(0, cv.bounds.minY))
  }

  private func cellAttributes(row: Int, col: Int) -> UICollectionViewLayoutAttributes {
    let g = geometry
    let attrs = UICollectionViewLayoutAttributes(forCellWith: IndexPath(item: row * g.columnCount + col, section: 0))
    var x = g.columnX(col)
    var z = 0
    if col < g.frozenCount {
      x += pin.x
      z = 1
    }
    attrs.frame = CGRect(x: x, y: g.rowY(row), width: g.columnWidth, height: g.rowHeight)
    attrs.zIndex = z
    return attrs
  }

  private func headerAttributes(col: Int) -> UICollectionViewLayoutAttributes {
    let g = geometry
    let attrs = UICollectionViewLayoutAttributes(forSupplementaryViewOfKind: headerKind, with: IndexPath(item: col, section: 0))
    var x = g.columnX(col)
    var z = 2
    if col < g.frozenCount {
      x += pin.x
      z = 3
    }
    attrs.frame = CGRect(x: x, y: pin.y, width: g.columnWidth, height: g.headerHeight)
    attrs.zIndex = z
    return attrs
  }

  override func layoutAttributesForElements(in rect: CGRect) -> [UICollectionViewLayoutAttributes]? {
    let g = geometry
    guard g.rowCount > 0, g.columnCount > 0 else { return [] }
    let rows = g.rows(minY: rect.minY, maxY: rect.maxY)
    let cols = g.bodyColumns(minX: rect.minX, maxX: rect.maxX)
    var result: [UICollectionViewLayoutAttributes] = []
    result.reserveCapacity((rows.count + 1) * (cols.count + g.frozenCount))
    for c in 0..<g.frozenCount { result.append(headerAttributes(col: c)) }
    for c in cols { result.append(headerAttributes(col: c)) }
    for r in rows {
      for c in 0..<g.frozenCount { result.append(cellAttributes(row: r, col: c)) }
      for c in cols { result.append(cellAttributes(row: r, col: c)) }
    }
    return result
  }

  override func layoutAttributesForItem(at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
    let g = geometry
    guard g.columnCount > 0 else { return nil }
    return cellAttributes(row: indexPath.item / g.columnCount, col: indexPath.item % g.columnCount)
  }

  override func layoutAttributesForSupplementaryView(ofKind elementKind: String, at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
    elementKind == headerKind ? headerAttributes(col: indexPath.item) : nil
  }
}

/// Draws its text in `draw(_:)`; no UILabel, so one backing store per cell.
final class GridTextView: UIView {
  var text = ""
  var row = 0
  var negative = false
  private(set) var drawnGeneration = -1
  private(set) var generation = 0

  override init(frame: CGRect) {
    super.init(frame: frame)
    isOpaque = true
    contentMode = .redraw
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  func configure(text: String, row: Int, negative: Bool, background: UIColor) {
    self.text = text
    self.row = row
    self.negative = negative
    backgroundColor = background
    generation += 1
    setNeedsDisplay()
  }

  var isDrawnCurrent: Bool { drawnGeneration == generation }

  override func draw(_ rect: CGRect) {
    GridStyle.drawCell(text: text, row: row, negative: negative, in: bounds)
    drawnGeneration = generation
  }
}

final class GridCollectionCell: UICollectionViewCell {
  let textView = GridTextView()
  var row = 0
  var col = 0

  override init(frame: CGRect) {
    super.init(frame: frame)
    textView.frame = contentView.bounds
    textView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    contentView.addSubview(textView)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}

final class GridHeaderCell: UICollectionReusableView {
  let textView = GridTextView()
  var col = 0

  override init(frame: CGRect) {
    super.init(frame: frame)
    textView.frame = bounds
    textView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(textView)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}

final class CollectionGridRenderer: UIView, GridRenderer, UICollectionViewDataSource, UICollectionViewDelegate {
  let displayName = "iOS A · UICollectionView"
  private let layout = FrozenGridLayout()
  private let collectionView: UICollectionView
  var benchmarkRunning = false

  var geometry = GridGeometry() {
    didSet {
      guard geometry != oldValue else { return }
      layout.geometry = geometry
      reloadAll()
    }
  }

  var data: GridData? {
    didSet { reloadAll() }
  }

  override init(frame: CGRect) {
    collectionView = UICollectionView(frame: frame, collectionViewLayout: layout)
    super.init(frame: frame)
    collectionView.backgroundColor = GridStyle.background
    collectionView.dataSource = self
    collectionView.delegate = self
    collectionView.alwaysBounceVertical = true
    collectionView.alwaysBounceHorizontal = true
    collectionView.showsHorizontalScrollIndicator = true
    collectionView.contentInsetAdjustmentBehavior = .never
    collectionView.isDirectionalLockEnabled = true
    collectionView.register(GridCollectionCell.self, forCellWithReuseIdentifier: "cell")
    collectionView.register(GridHeaderCell.self, forSupplementaryViewOfKind: headerKind, withReuseIdentifier: "header")
    collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(collectionView)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  func reloadAll() {
    collectionView.reloadData()
  }

  var contentOffset: CGPoint { collectionView.contentOffset }

  func setContentOffset(_ offset: CGPoint) {
    collectionView.setContentOffset(offset, animated: false)
  }

  // MARK: PerfMonitorTarget

  var isMoving: Bool {
    benchmarkRunning || collectionView.isDragging || collectionView.isDecelerating
  }

  var hasDrawnContent: Bool {
    collectionView.visibleCells.contains { ($0 as? GridCollectionCell)?.textView.isDrawnCurrent == true }
  }

  func currentBlankFraction() -> Double {
    var drawn = Set<Int64>()
    for case let cell as GridCollectionCell in collectionView.visibleCells where cell.textView.isDrawnCurrent && !cell.isHidden {
      drawn.insert(GridGeometry.key(row: cell.row, col: cell.col))
    }
    for case let header as GridHeaderCell in collectionView.visibleSupplementaryViews(ofKind: headerKind) where header.textView.isDrawnCurrent {
      drawn.insert(GridGeometry.key(row: -1, col: header.col))
    }
    return geometry.blankFraction(offset: collectionView.contentOffset, viewport: bounds.size) { row, col in
      drawn.contains(GridGeometry.key(row: row, col: col))
    }
  }

  // MARK: Data source

  func numberOfSections(in collectionView: UICollectionView) -> Int { 1 }

  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
    geometry.rowCount * geometry.columnCount
  }

  func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
    // swiftlint:disable:next force_cast
    let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "cell", for: indexPath) as! GridCollectionCell
    let g = geometry
    let row = indexPath.item / g.columnCount
    let col = indexPath.item % g.columnCount
    cell.row = row
    cell.col = col
    let value = data?.number(row: row, col: col)
    cell.textView.configure(
      text: data?.text(row: row, col: col) ?? "",
      row: row,
      negative: (value ?? 0) < 0,
      background: GridStyle.backgroundColor(row: row, col: col, frozenCount: g.frozenCount)
    )
    return cell
  }

  func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
    // swiftlint:disable:next force_cast
    let header = collectionView.dequeueReusableSupplementaryView(ofKind: kind, withReuseIdentifier: "header", for: indexPath) as! GridHeaderCell
    header.col = indexPath.item
    header.textView.configure(text: GridStyle.headerTitle(col: indexPath.item), row: -1, negative: false, background: GridStyle.headerBackground)
    return header
  }
}

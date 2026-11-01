import UIKit

// Candidate B: an empty UIScrollView provides the gestures, deceleration and bounce.
// Cells are CALayers from a reuse pool, drawn with CoreText/UIKit string drawing, and live in
// four non-scrolling clip containers (corner, header, frozen column, body) that sit above the
// scroll view. Layer positions are viewport-relative, so they stay small even at row 1,000,000.

final class GridCellLayer: CALayer {
  var text = ""
  var row = 0
  var col = 0
  var negative = false
  private var fill: CGColor = UIColor.white.cgColor
  private(set) var generation = 0
  private(set) var drawnGeneration = -1

  override init() {
    super.init()
    isOpaque = true
    contentsScale = UIScreen.main.scale
    needsDisplayOnBoundsChange = false
  }

  override init(layer: Any) {
    super.init(layer: layer)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  // No implicit animations for position, bounds or contents.
  override func action(forKey event: String) -> CAAction? { nil }

  func configure(row: Int, col: Int, text: String, negative: Bool, background: CGColor) {
    self.row = row
    self.col = col
    self.text = text
    self.negative = negative
    fill = background
    generation += 1
    setNeedsDisplay()
  }

  var isDrawnCurrent: Bool { drawnGeneration == generation && !isHidden }

  override func draw(in ctx: CGContext) {
    // The layer is opaque, so its backing store isn't cleared before drawing and covers
    // backgroundColor. Paint the cell background into the contents ourselves.
    ctx.setFillColor(fill)
    ctx.fill(bounds)
    UIGraphicsPushContext(ctx)
    GridStyle.drawCell(text: text, row: row, negative: negative, in: bounds)
    UIGraphicsPopContext()
    drawnGeneration = generation
  }
}

private final class ClipContainer: UIView {
  override init(frame: CGRect) {
    super.init(frame: frame)
    clipsToBounds = true
    isUserInteractionEnabled = false
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}

final class LayerGridRenderer: UIView, GridRenderer, UIScrollViewDelegate {
  let displayName = "iOS B · layer pool on UIScrollView"
  private let scrollView = UIScrollView()
  private let corner = ClipContainer()
  private let header = ClipContainer()
  private let frozen = ClipContainer()
  private let body = ClipContainer()

  private var active: [Int64: GridCellLayer] = [:]
  private var pool: [GridCellLayer] = []
  var benchmarkRunning = false

  var geometry = GridGeometry() {
    didSet {
      guard geometry != oldValue else { return }
      scrollView.contentSize = CGSize(width: geometry.contentWidth, height: geometry.contentHeight)
      reloadAll()
    }
  }

  var data: GridData? {
    didSet { reloadAll() }
  }

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = GridStyle.background
    scrollView.delegate = self
    scrollView.alwaysBounceVertical = true
    scrollView.alwaysBounceHorizontal = true
    scrollView.isDirectionalLockEnabled = true
    scrollView.contentInsetAdjustmentBehavior = .never
    addSubview(scrollView)
    // Containers sit above the scroll view and don't take touches, so gestures reach it.
    for v in [body, frozen, header, corner] { addSubview(v) }
    // Keep the scroll indicators visible above the cells.
    bringSubviewToFront(scrollView)
    scrollView.backgroundColor = .clear
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func layoutSubviews() {
    super.layoutSubviews()
    scrollView.frame = bounds
    let g = geometry
    let fw = min(g.frozenWidth, bounds.width)
    let hh = min(g.headerHeight, bounds.height)
    corner.frame = CGRect(x: 0, y: 0, width: fw, height: hh)
    header.frame = CGRect(x: fw, y: 0, width: bounds.width - fw, height: hh)
    frozen.frame = CGRect(x: 0, y: hh, width: fw, height: bounds.height - hh)
    body.frame = CGRect(x: fw, y: hh, width: bounds.width - fw, height: bounds.height - hh)
    updateVisibleCells()
  }

  func scrollViewDidScroll(_ scrollView: UIScrollView) {
    updateVisibleCells()
  }

  var contentOffset: CGPoint { scrollView.contentOffset }

  func setContentOffset(_ offset: CGPoint) {
    scrollView.setContentOffset(offset, animated: false)
  }

  func reloadAll() {
    for (_, layer) in active {
      layer.isHidden = true
      pool.append(layer)
    }
    active.removeAll(keepingCapacity: true)
    setNeedsLayout()
  }

  private func dequeue(into container: UIView) -> GridCellLayer {
    let layer = pool.popLast() ?? GridCellLayer()
    if layer.superlayer !== container.layer {
      layer.removeFromSuperlayer()
      container.layer.addSublayer(layer)
    }
    layer.isHidden = false
    return layer
  }

  private func updateVisibleCells() {
    let g = geometry
    guard g.rowCount > 0, g.columnCount > 0, bounds.width > 0 else { return }
    let offset = scrollView.contentOffset
    let rows = g.visibleRows(offsetY: offset.y, height: bounds.height)
    let cols = g.visibleBodyColumns(offsetX: offset.x, width: bounds.width)

    CATransaction.begin()
    CATransaction.setDisableActions(true)

    var needed = Set<Int64>()
    needed.reserveCapacity((rows.count + 1) * (cols.count + g.frozenCount))

    func place(row: Int, col: Int, container: UIView, origin: CGPoint) {
      let key = GridGeometry.key(row: row, col: col)
      needed.insert(key)
      let screen = g.screenRect(row: row, col: col, offset: offset)
      // Convert from grid-view coordinates to container coordinates.
      let frame = screen.offsetBy(dx: -origin.x, dy: -origin.y)
      if let layer = active[key] {
        if layer.frame.origin != frame.origin { layer.frame = frame }
        return
      }
      let layer = dequeue(into: container)
      layer.frame = frame
      if row < 0 {
        layer.configure(row: row, col: col, text: GridStyle.headerTitle(col: col), negative: false, background: GridStyle.headerBackground.cgColor)
      } else {
        let value = data?.number(row: row, col: col) ?? 0
        layer.configure(
          row: row, col: col,
          text: data?.text(row: row, col: col) ?? "",
          negative: value < 0,
          background: GridStyle.backgroundColor(row: row, col: col, frozenCount: g.frozenCount).cgColor
        )
      }
      active[key] = layer
    }

    for c in 0..<g.frozenCount { place(row: -1, col: c, container: corner, origin: corner.frame.origin) }
    for c in cols { place(row: -1, col: c, container: header, origin: header.frame.origin) }
    for r in rows {
      for c in 0..<g.frozenCount { place(row: r, col: c, container: frozen, origin: frozen.frame.origin) }
      for c in cols { place(row: r, col: c, container: body, origin: body.frame.origin) }
    }

    if active.count > needed.count {
      for (key, layer) in active where !needed.contains(key) {
        layer.isHidden = true
        pool.append(layer)
        active.removeValue(forKey: key)
      }
    }
    CATransaction.commit()
  }

  // MARK: PerfMonitorTarget

  var isMoving: Bool {
    benchmarkRunning || scrollView.isDragging || scrollView.isDecelerating
  }

  var hasDrawnContent: Bool {
    active.values.contains { $0.isDrawnCurrent && $0.row >= 0 }
  }

  func currentBlankFraction() -> Double {
    geometry.blankFraction(offset: scrollView.contentOffset, viewport: bounds.size) { row, col in
      active[GridGeometry.key(row: row, col: col)]?.isDrawnCurrent == true
    }
  }
}

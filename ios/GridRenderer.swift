import UIKit

/// A rendering candidate. The spike view hosts exactly one at a time.
protocol GridRenderer: UIView, PerfMonitorTarget {
  var displayName: String { get }
  var geometry: GridGeometry { get set }
  var data: GridData? { get set }
  /// Current content offset (top-left of the viewport in content coordinates).
  var contentOffset: CGPoint { get }
  /// Moves the content without animation. Used by the benchmark driver.
  func setContentOffset(_ offset: CGPoint)
  var benchmarkRunning: Bool { get set }
  func reloadAll()
}

/// Colors and fonts shared by the candidates so they draw the same pixels.
enum GridStyle {
  static let font = UIFont.monospacedDigitSystemFont(ofSize: 13, weight: .regular)
  static let headerFont = UIFont.systemFont(ofSize: 13, weight: .semibold)
  static let padding: CGFloat = 8

  static let background = UIColor.systemBackground
  static let stripe = UIColor.secondarySystemBackground
  static let headerBackground = UIColor.tertiarySystemBackground
  static let frozenBackground = UIColor.secondarySystemBackground
  static let text = UIColor.label
  static let negative = UIColor.systemRed
  static let separator = UIColor.separator

  static let textAttributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: text]
  static let negativeAttributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: negative]
  static let headerAttributes: [NSAttributedString.Key: Any] = [.font: headerFont, .foregroundColor: text]

  static func backgroundColor(row: Int, col: Int, frozenCount: Int) -> UIColor {
    if row < 0 { return headerBackground }
    if col < frozenCount { return frozenBackground }
    return row % 2 == 1 ? stripe : background
  }

  static func headerTitle(col: Int) -> String { "Col \(col)" }

  /// Draws one cell's text into the current UIKit context. Numbers are right-aligned.
  static func drawCell(text: String, row: Int, negative: Bool, in rect: CGRect) {
    let attrs = row < 0 ? headerAttributes : (negative ? negativeAttributes : textAttributes)
    let s = text as NSString
    let size = s.size(withAttributes: attrs)
    let x = row < 0 ? rect.minX + padding : rect.maxX - padding - size.width
    let y = rect.minY + (rect.height - size.height) / 2
    s.draw(at: CGPoint(x: x, y: y), withAttributes: attrs)
  }
}

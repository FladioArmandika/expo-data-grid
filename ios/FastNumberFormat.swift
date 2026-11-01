import Foundation

/// Allocation-light fixed-point formatter: 2 decimals, thousands separators.
/// `NumberFormatter` is far too slow to call for every cell that scrolls into view.
enum FastNumberFormat {
  static func format(_ value: Double, decimals: Int = 2, thousands: Bool = true) -> String {
    guard value.isFinite else { return value.isNaN ? "" : (value > 0 ? "∞" : "-∞") }
    var scale = 1.0
    for _ in 0..<decimals { scale *= 10 }
    let scaled = (abs(value) * scale).rounded()
    guard scaled < 9.0e17 else { return String(value) }

    var n = UInt64(scaled)
    var buffer = [UInt8](repeating: 0, count: 40)
    var i = buffer.count

    for _ in 0..<decimals {
      i -= 1
      buffer[i] = UInt8(ascii: "0") + UInt8(n % 10)
      n /= 10
    }
    if decimals > 0 {
      i -= 1
      buffer[i] = UInt8(ascii: ".")
    }
    var digits = 0
    repeat {
      if thousands && digits > 0 && digits % 3 == 0 {
        i -= 1
        buffer[i] = UInt8(ascii: ",")
      }
      i -= 1
      buffer[i] = UInt8(ascii: "0") + UInt8(n % 10)
      n /= 10
      digits += 1
    } while n > 0
    if value < 0 && scaled > 0 {
      i -= 1
      buffer[i] = UInt8(ascii: "-")
    }
    return String(decoding: buffer[i...], as: UTF8.self)
  }
}

package expo.modules.datagrid

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Allocation-free fixed-point formatter. Writes into [out] right-aligned and returns the start index;
 * the text is out[start until out.size]. String.format is far too slow to call per visible cell per frame.
 */
object FastNumberFormat {
  const val BUFFER_SIZE = 40

  fun format(value: Double, out: CharArray, decimals: Int = 2, thousands: Boolean = true): Int {
    var i = out.size
    if (value.isNaN()) return i
    if (value.isInfinite()) {
      out[--i] = '∞'
      if (value < 0) out[--i] = '-'
      return i
    }
    var scale = 1.0
    repeat(decimals) { scale *= 10 }
    val scaledDouble = abs(value) * scale
    if (scaledDouble >= 9.0e17) {
      val s = value.toString()
      s.toCharArray(out, out.size - s.length)
      return out.size - s.length
    }
    var n = scaledDouble.roundToLong()
    val nonZero = n > 0
    repeat(decimals) {
      out[--i] = '0' + (n % 10).toInt()
      n /= 10
    }
    if (decimals > 0) out[--i] = '.'
    var digits = 0
    do {
      if (thousands && digits > 0 && digits % 3 == 0) out[--i] = ','
      out[--i] = '0' + (n % 10).toInt()
      n /= 10
      digits++
    } while (n > 0)
    if (value < 0 && nonZero) out[--i] = '-'
    return i
  }

  fun format(value: Double, decimals: Int = 2, thousands: Boolean = true): String {
    val out = CharArray(BUFFER_SIZE)
    val start = format(value, out, decimals, thousands)
    return String(out, start, out.size - start)
  }
}

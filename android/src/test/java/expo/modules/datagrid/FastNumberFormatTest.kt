package expo.modules.datagrid

import org.junit.Assert.assertEquals
import org.junit.Test

class FastNumberFormatTest {
  private fun f(v: Double) = FastNumberFormat.format(v)

  @Test fun basics() {
    assertEquals("0.00", f(0.0))
    assertEquals("1.50", f(1.5))
    assertEquals("-1.50", f(-1.5))
    assertEquals("999.99", f(999.99))
    assertEquals("1,000.00", f(1000.0))
    assertEquals("-49,999.99", f(-49999.99))
    assertEquals("1,234,567.89", f(1234567.891))
  }

  @Test fun rounding() {
    assertEquals("0.01", f(0.005000001))
    assertEquals("1.00", f(0.999))
  }

  @Test fun negativeZeroHasNoSign() {
    assertEquals("0.00", f(-0.001))
  }

  @Test fun nonFinite() {
    assertEquals("", f(Double.NaN))
    assertEquals("∞", f(Double.POSITIVE_INFINITY))
    assertEquals("-∞", f(Double.NEGATIVE_INFINITY))
  }

  @Test fun matchesStringFormatForRandomValues() {
    val rnd = java.util.Random(42)
    repeat(10_000) {
      val v = (rnd.nextLong() % 10_000_000L) / 100.0
      assertEquals(String.format(java.util.Locale.US, "%,.2f", v), f(v))
    }
  }

  @Test fun charArrayVariantIsRightAligned() {
    val out = CharArray(FastNumberFormat.BUFFER_SIZE)
    val start = FastNumberFormat.format(-12.5, out)
    assertEquals("-12.50", String(out, start, out.size - start))
  }
}

package expo.modules.datagrid.spike

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import expo.modules.datagrid.GridData
import expo.modules.datagrid.GridGeometry

/** A rendering candidate. The spike view hosts one at a time. Geometry is in pixels. */
interface GridRenderer : PerfMonitorTarget {
  val view: View
  val displayName: String
  var geometry: GridGeometry
  var data: GridData?
  val offsetX: Double
  val offsetY: Double
  /** Moves content without animation (benchmark driver). Clamped to the content range. */
  fun setOffset(x: Double, y: Double)
  var benchmarkRunning: Boolean
  fun reloadAll()
}

/** Shared colors and text metrics so every candidate draws the same pixels. */
class GridStyle(context: Context) {
  private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
  private val density = context.resources.displayMetrics.density
  private val scaledDensity = density * context.resources.configuration.fontScale

  val background = if (dark) Color.rgb(0, 0, 0) else Color.rgb(255, 255, 255)
  val stripe = if (dark) Color.rgb(28, 28, 30) else Color.rgb(242, 242, 247)
  val headerBackground = if (dark) Color.rgb(44, 44, 46) else Color.rgb(229, 229, 234)
  val frozenBackground = stripe
  val textColor = if (dark) Color.WHITE else Color.BLACK
  val negativeColor = if (dark) Color.rgb(255, 69, 58) else Color.rgb(255, 59, 48)
  val separator = if (dark) Color.rgb(56, 56, 58) else Color.rgb(198, 198, 200)

  val textSizePx = 13f * scaledDensity
  val paddingPx = 8f * density

  fun textPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = textSizePx
    color = textColor
    fontFeatureSettings = "tnum"
  }

  fun headerPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = textSizePx
    color = textColor
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  }

  fun backgroundColor(row: Int, col: Int, frozenCount: Int): Int = when {
    row < 0 -> headerBackground
    col < frozenCount -> frozenBackground
    row % 2 == 1 -> stripe
    else -> background
  }

  companion object {
    fun headerTitle(col: Int) = "Col $col"
  }
}

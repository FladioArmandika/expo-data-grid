package expo.modules.datagrid

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import expo.modules.datagrid.spike.PerfMonitor
import expo.modules.datagrid.spike.SpikeGridView
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.typedarray.Float64Array
import expo.modules.kotlin.typedarray.Int32Array

class ExpoDataGridModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoDataGrid")

    Function("getDeviceInfo") {
      val context = appContext.reactContext
      val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
      mapOf(
        "platform" to "android",
        "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "osVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "totalMemoryMB" to mem.totalMem / 1048576.0,
        "heapLimitMB" to (am?.memoryClass ?: 0),
        "largeHeapLimitMB" to (am?.largeMemoryClass ?: 0),
        "maxRefreshRate" to (context?.let { PerfMonitor.displayRefreshRate(it) } ?: 60.0),
        "isDebugBuild" to (((context?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0),
      )
    }

    Function("getResidentMemoryMB") {
      PerfMonitor.readResidentBytes() / 1048576.0
    }

    Class(GridData::class) {
      Constructor {
        GridData(appContext)
      }

      Property("rowCount").get { data: GridData -> data.snapshotRows }
      Property("columnCount").get { data: GridData -> data.snapshotColumns }
      Property("byteSize").get { data: GridData -> data.snapshotBytes.toDouble() }

      Function("reset") { data: GridData, rows: Int, columns: Int ->
        data.reset(rows, columns)
      }

      AsyncFunction("fillSyntheticAsync") { data: GridData, rows: Int, columns: Int ->
        data.fillSynthetic(rows, columns)
      }

      // Synchronous on purpose: the typed array is a view of JS-owned memory, valid only during the call.
      Function("setNumberColumn") { data: GridData, column: Int, values: Float64Array ->
        data.setNumberColumn(column, values)
      }

      Function("setTextColumn") { data: GridData, column: Int, indices: Int32Array, table: List<String> ->
        data.setTextColumn(column, indices, table)
      }
    }

    View(SpikeGridView::class) {
      Events("onBenchmarkResult", "onFirstFrame")

      Prop("data") { view: SpikeGridView, data: GridData? -> view.setData(data) }
      Prop("renderer") { view: SpikeGridView, renderer: String? -> view.setRenderer(renderer ?: "A") }
      Prop("frozenColumns") { view: SpikeGridView, value: Int? -> view.setFrozenColumns(value ?: 1) }
      Prop("rowHeight") { view: SpikeGridView, value: Double? -> view.setRowHeight(value ?: 32.0) }
      Prop("headerHeight") { view: SpikeGridView, value: Double? -> view.setHeaderHeight(value ?: 36.0) }
      Prop("columnWidth") { view: SpikeGridView, value: Double? -> view.setColumnWidth(value ?: 88.0) }
      Prop("showPerfOverlay") { view: SpikeGridView, value: Boolean? -> view.showOverlay = value ?: false }
      Prop("benchmarkRunId") { view: SpikeGridView, value: Int? -> view.setBenchmarkRunId(value ?: 0) }
    }
  }
}

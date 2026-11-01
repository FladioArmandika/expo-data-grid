import ExpoModulesCore
import UIKit

public class ExpoDataGridModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoDataGrid")

    Function("getDeviceInfo") { () -> [String: Any] in
      var system = utsname()
      uname(&system)
      let model = withUnsafePointer(to: &system.machine) {
        $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
      }
      return [
        "platform": "ios",
        "model": model,
        "osVersion": UIDevice.current.systemVersion,
        "totalMemoryMB": Double(ProcessInfo.processInfo.physicalMemory) / 1_048_576,
        "maxRefreshRate": UIScreen.main.maximumFramesPerSecond,
        "isDebugBuild": ExpoDataGridModule.isDebugBuild,
      ]
    }

    Function("getResidentMemoryMB") { () -> Double in
      Double(PerfMonitor.residentBytes()) / 1_048_576
    }

    Class(GridData.self) {
      Constructor { () -> GridData in
        GridData()
      }

      Property("rowCount") { (data: GridData) -> Int in
        data.snapshot.rows
      }

      Property("columnCount") { (data: GridData) -> Int in
        data.snapshot.cols
      }

      Property("byteSize") { (data: GridData) -> Int in
        data.snapshot.bytes
      }

      Function("reset") { (data: GridData, rows: Int, columns: Int) in
        data.reset(rows: rows, cols: columns)
      }

      AsyncFunction("fillSyntheticAsync") { (data: GridData, rows: Int, columns: Int) -> Double in
        data.fillSynthetic(rows: rows, cols: columns)
      }

      // Synchronous on purpose: the typed array's memory belongs to the JS runtime and is only
      // safe to read on the JS thread during the call.
      Function("setNumberColumn") { (data: GridData, column: Int, values: Float64Array) -> Double in
        try data.setNumberColumn(col: column, values: values)
      }

      Function("setTextColumn") { (data: GridData, column: Int, indices: Int32Array, table: [String]) -> Double in
        try data.setTextColumn(col: column, indices: indices, table: table)
      }
    }

    View(SpikeGridView.self) {
      Events("onBenchmarkResult", "onFirstFrame")

      Prop("data") { (view: SpikeGridView, data: GridData?) in
        view.setData(data)
      }

      Prop("renderer") { (view: SpikeGridView, renderer: String?) in
        view.setRenderer(renderer ?? "A")
      }

      Prop("frozenColumns") { (view: SpikeGridView, value: Int?) in
        view.updateGeometry { $0.frozenColumns = value ?? 1 }
      }

      Prop("rowHeight") { (view: SpikeGridView, value: Double?) in
        view.updateGeometry { $0.rowHeight = CGFloat(value ?? 32) }
      }

      Prop("headerHeight") { (view: SpikeGridView, value: Double?) in
        view.updateGeometry { $0.headerHeight = CGFloat(value ?? 36) }
      }

      Prop("columnWidth") { (view: SpikeGridView, value: Double?) in
        view.updateGeometry { $0.columnWidth = CGFloat(value ?? 88) }
      }

      Prop("showPerfOverlay") { (view: SpikeGridView, value: Bool?) in
        view.showOverlay = value ?? false
      }

      Prop("benchmarkRunId") { (view: SpikeGridView, value: Int?) in
        view.setBenchmarkRunId(value ?? 0)
      }
    }
  }

  static var isDebugBuild: Bool {
    #if DEBUG
    return true
    #else
    return false
    #endif
  }
}

import ExpoModulesCore
import QuartzCore

/// Column storage owned by native code. Memory is allocated with `UnsafeMutablePointer`
/// so it never lives on the JS heap and is freed when the column is replaced.
final class NumberColumn {
  let values: UnsafeMutablePointer<Double>
  let count: Int

  init(count: Int) {
    self.count = count
    values = UnsafeMutablePointer<Double>.allocate(capacity: max(count, 1))
  }

  deinit {
    values.deallocate()
  }

  var byteSize: Int { count * MemoryLayout<Double>.stride }
}

final class TextColumn {
  let indices: UnsafeMutablePointer<Int32>
  let count: Int
  let table: [String]

  init(count: Int, table: [String]) {
    self.count = count
    self.table = table
    indices = UnsafeMutablePointer<Int32>.allocate(capacity: max(count, 1))
  }

  deinit {
    indices.deallocate()
  }

  var byteSize: Int {
    count * MemoryLayout<Int32>.stride + table.reduce(0) { $0 + $1.utf8.count + 16 }
  }
}

enum ColumnStorage {
  case empty
  case number(NumberColumn)
  case text(TextColumn)
}

protocol GridDataListener: AnyObject {
  func gridDataDidChange(_ data: GridData)
}

/// Native data store shared with JS. All reads used for rendering happen on the main thread.
/// Writes coming from the JS thread (or a background queue) copy into native memory first and
/// then publish the new column on the main thread, so the renderer never sees a half-written column.
public final class GridData: SharedObject {
  // Main-thread state.
  private(set) var rowCount: Int = 0
  private(set) var columns: [ColumnStorage] = []
  private let listeners = NSHashTable<AnyObject>.weakObjects()

  // Snapshot readable from any thread (JS property getters).
  private let lock = NSLock()
  private var snapshotRows = 0
  private var snapshotCols = 0
  private var snapshotBytes = 0

  var columnCount: Int { columns.count }

  func addListener(_ listener: GridDataListener) {
    listeners.add(listener)
  }

  func removeListener(_ listener: GridDataListener) {
    listeners.remove(listener)
  }

  // MARK: - Reads (main thread)

  @inline(__always)
  func number(row: Int, col: Int) -> Double? {
    guard col < columns.count else { return nil }
    switch columns[col] {
    case .number(let column):
      return row < column.count ? column.values[row] : nil
    default:
      return nil
    }
  }

  func text(row: Int, col: Int) -> String {
    guard col < columns.count else { return "" }
    switch columns[col] {
    case .number(let column):
      return row < column.count ? FastNumberFormat.format(column.values[row]) : ""
    case .text(let column):
      guard row < column.count else { return "" }
      let index = Int(column.indices[row])
      return index >= 0 && index < column.table.count ? column.table[index] : ""
    case .empty:
      return ""
    }
  }

  // MARK: - Snapshot (any thread)

  var snapshot: (rows: Int, cols: Int, bytes: Int) {
    lock.lock()
    defer { lock.unlock() }
    return (snapshotRows, snapshotCols, snapshotBytes)
  }

  // MARK: - Writes

  func reset(rows: Int, cols: Int) {
    publish {
      self.rowCount = rows
      self.columns = Array(repeating: .empty, count: cols)
    }
  }

  /// Fills `cols` numeric columns of `rows` rows with deterministic pseudo-random prices.
  /// Runs on the caller's (background) queue; returns milliseconds spent allocating and filling.
  func fillSynthetic(rows: Int, cols: Int) -> Double {
    let start = CACurrentMediaTime()
    var newColumns: [ColumnStorage] = []
    newColumns.reserveCapacity(cols)
    for c in 0..<cols {
      let column = NumberColumn(count: rows)
      var state = UInt64(0x9E37_79B9_7F4A_7C15) &+ UInt64(c) &* 0xBF58_476D_1CE4_E5B9
      let p = column.values
      for r in 0..<rows {
        // xorshift64*
        state ^= state >> 12
        state ^= state << 25
        state ^= state >> 27
        let x = state &* 0x2545_F491_4F6C_DD1D
        // Values in roughly [-50_000, 50_000) with 2 decimals, signs mixed.
        let cents = Int64(bitPattern: x % 10_000_000) - 5_000_000
        p[r] = Double(cents) / 100.0
      }
      newColumns.append(.number(column))
    }
    let elapsed = (CACurrentMediaTime() - start) * 1000
    publish {
      self.rowCount = rows
      self.columns = newColumns
    }
    return elapsed
  }

  /// Copies a JS `Float64Array` into native memory. Must be called on the JS thread, because the
  /// typed array's pointer refers to memory owned by the JS runtime. Returns the copy time in ms.
  func setNumberColumn(col: Int, values: Float64Array) throws -> Double {
    let start = CACurrentMediaTime()
    let count = values.length
    let column = NumberColumn(count: count)
    memcpy(column.values, values.rawPointer, count * MemoryLayout<Double>.stride)
    let elapsed = (CACurrentMediaTime() - start) * 1000
    publish {
      self.ensureColumn(col)
      self.columns[col] = .number(column)
      self.rowCount = max(self.rowCount, count)
    }
    return elapsed
  }

  /// Copies dictionary-encoded strings: `indices[i]` points into `table`.
  /// The `[String]` conversion happens before this runs, so JS should time the whole call too.
  func setTextColumn(col: Int, indices: Int32Array, table: [String]) throws -> Double {
    let start = CACurrentMediaTime()
    let count = indices.length
    let column = TextColumn(count: count, table: table)
    memcpy(column.indices, indices.rawPointer, count * MemoryLayout<Int32>.stride)
    let elapsed = (CACurrentMediaTime() - start) * 1000
    publish {
      self.ensureColumn(col)
      self.columns[col] = .text(column)
      self.rowCount = max(self.rowCount, count)
    }
    return elapsed
  }

  private func ensureColumn(_ col: Int) {
    if col >= columns.count {
      columns.append(contentsOf: Array(repeating: ColumnStorage.empty, count: col - columns.count + 1))
    }
  }

  private func computeBytes() -> Int {
    columns.reduce(0) { total, column in
      switch column {
      case .number(let c): return total + c.byteSize
      case .text(let c): return total + c.byteSize
      case .empty: return total
      }
    }
  }

  private func publish(_ change: @escaping () -> Void) {
    let apply = { [weak self] in
      guard let self else { return }
      change()
      let bytes = self.computeBytes()
      self.lock.lock()
      self.snapshotRows = self.rowCount
      self.snapshotCols = self.columns.count
      self.snapshotBytes = bytes
      self.lock.unlock()
      for case let listener as GridDataListener in self.listeners.allObjects {
        listener.gridDataDidChange(self)
      }
    }
    if Thread.isMainThread {
      apply()
    } else {
      DispatchQueue.main.async(execute: apply)
    }
  }

  public override func sharedObjectDidRelease() {
    super.sharedObjectDidRelease()
  }
}

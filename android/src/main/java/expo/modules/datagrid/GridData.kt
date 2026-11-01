package expo.modules.datagrid

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.sharedobjects.SharedObject
import expo.modules.kotlin.typedarray.Float64Array
import expo.modules.kotlin.typedarray.Int32Array
import java.nio.ByteBuffer
import java.nio.DoubleBuffer
import java.nio.IntBuffer
import java.util.concurrent.CopyOnWriteArrayList

sealed class ColumnStorage {
  abstract val byteSize: Long
  open fun free() = Unit

  object Empty : ColumnStorage() {
    override val byteSize = 0L
  }

  class Numbers(val count: Int) : ColumnStorage() {
    private val buffer: ByteBuffer = NativeBuffers.allocate(count.toLong() * 8)
    val values: DoubleBuffer = buffer.asDoubleBuffer()
    override val byteSize = count.toLong() * 8
    fun raw(): ByteBuffer = buffer.duplicate().also { it.clear() }
    override fun free() = NativeBuffers.free(buffer)
  }

  class Text(val count: Int, val table: Array<String>) : ColumnStorage() {
    private val buffer: ByteBuffer = NativeBuffers.allocate(count.toLong() * 4)
    val indices: IntBuffer = buffer.asIntBuffer()
    override val byteSize = count.toLong() * 4 + table.sumOf { it.length * 2L + 40 }
    fun raw(): ByteBuffer = buffer.duplicate().also { it.clear() }
    override fun free() = NativeBuffers.free(buffer)
  }
}

fun interface GridDataListener {
  fun onGridDataChanged(data: GridData)
}

/**
 * Native data store shared with JS. Render reads happen on the main thread only.
 * Writes copy into off-heap memory on the calling thread, then publish on the main thread
 * and free the replaced column there, so a renderer never reads freed memory.
 */
class GridData(appContext: AppContext) : SharedObject(appContext) {
  private val main = Handler(Looper.getMainLooper())
  private val listeners = CopyOnWriteArrayList<GridDataListener>()

  // Main-thread state.
  var rowCount = 0
    private set
  private var columns: Array<ColumnStorage> = emptyArray()
  val columnCount: Int get() = columns.size

  // Snapshot for JS getters (any thread).
  @Volatile var snapshotRows = 0
    private set
  @Volatile var snapshotColumns = 0
    private set
  @Volatile var snapshotBytes = 0L
    private set

  fun addListener(l: GridDataListener) = listeners.addIfAbsent(l)
  fun removeListener(l: GridDataListener) = listeners.remove(l)

  // ---- Reads (main thread) ----

  fun column(col: Int): ColumnStorage = if (col in columns.indices) columns[col] else ColumnStorage.Empty

  fun number(row: Int, col: Int): Double {
    val c = column(col)
    return if (c is ColumnStorage.Numbers && row < c.count) c.values.get(row) else Double.NaN
  }

  /** Writes the cell's display text into [out]; returns the start index (text is out[start until out.size]). */
  fun formatInto(row: Int, col: Int, out: CharArray): Int {
    return when (val c = column(col)) {
      is ColumnStorage.Numbers -> if (row < c.count) FastNumberFormat.format(c.values.get(row), out) else out.size
      is ColumnStorage.Text -> {
        if (row >= c.count) return out.size
        val idx = c.indices.get(row)
        val s = if (idx in c.table.indices) c.table[idx] else ""
        val n = minOf(s.length, out.size)
        s.toCharArray(out, out.size - n, 0, n)
        out.size - n
      }
      ColumnStorage.Empty -> out.size
    }
  }

  fun text(row: Int, col: Int): String {
    val out = CharArray(FastNumberFormat.BUFFER_SIZE)
    val start = formatInto(row, col, out)
    return String(out, start, out.size - start)
  }

  // ---- Writes ----

  fun reset(rows: Int, cols: Int) = publish {
    val old = columns
    rowCount = rows
    columns = Array(cols) { ColumnStorage.Empty }
    old
  }

  /** Deterministic pseudo-random prices. Call off the main thread. Returns ms spent. */
  fun fillSynthetic(rows: Int, cols: Int): Double {
    val start = SystemClock.elapsedRealtimeNanos()
    val fresh = Array<ColumnStorage>(cols) { c ->
      val column = ColumnStorage.Numbers(rows)
      val v = column.values
      var state = -0x61c8864680b583ebL + c * -0x40a7b892e31b1a47L
      for (r in 0 until rows) {
        state = state xor (state ushr 12)
        state = state xor (state shl 25)
        state = state xor (state ushr 27)
        val x = state * 0x2545F4914F6CDD1DL
        val cents = java.lang.Long.remainderUnsigned(x, 10_000_000L) - 5_000_000L
        v.put(r, cents / 100.0)
      }
      column
    }
    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    publish {
      val old = columns
      rowCount = rows
      columns = fresh
      old
    }
    return elapsed
  }

  /**
   * Copies a JS Float64Array into native memory. Runs on the JS thread: toDirectBuffer() is a
   * view of memory owned by the JS runtime and is only valid during this call. Returns copy ms.
   */
  fun setNumberColumn(col: Int, values: Float64Array): Double {
    val start = SystemClock.elapsedRealtimeNanos()
    val column = ColumnStorage.Numbers(values.length)
    val src = values.toDirectBuffer()
    src.limit(src.position() + values.length * 8)
    column.raw().put(src)
    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    publish { replace(col, column, values.length) }
    return elapsed
  }

  fun setTextColumn(col: Int, indices: Int32Array, table: List<String>): Double {
    val start = SystemClock.elapsedRealtimeNanos()
    val column = ColumnStorage.Text(indices.length, table.toTypedArray())
    val src = indices.toDirectBuffer()
    src.limit(src.position() + indices.length * 4)
    column.raw().put(src)
    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    publish { replace(col, column, indices.length) }
    return elapsed
  }

  private fun replace(col: Int, column: ColumnStorage, count: Int): Array<ColumnStorage> {
    val old = if (col in columns.indices) columns[col] else null
    if (col >= columns.size) {
      columns = Array(col + 1) { i -> if (i < columns.size) columns[i] else ColumnStorage.Empty }
    }
    columns[col] = column
    rowCount = maxOf(rowCount, count)
    return if (old != null) arrayOf(old) else emptyArray()
  }

  /** Runs [change] on the main thread; frees whatever columns it returns, then notifies. */
  private fun publish(change: () -> Array<ColumnStorage>) {
    val apply = Runnable {
      val replaced = change()
      snapshotRows = rowCount
      snapshotColumns = columns.size
      snapshotBytes = columns.sumOf { it.byteSize }
      listeners.forEach { it.onGridDataChanged(this) }
      replaced.forEach { if (it !in columns) it.free() }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
  }

  override fun sharedObjectDidRelease() {
    main.post {
      columns.forEach { it.free() }
      columns = emptyArray()
      rowCount = 0
      listeners.forEach { it.onGridDataChanged(this) }
    }
    super.sharedObjectDidRelease()
  }
}

package expo.modules.datagrid

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Direct ByteBuffers backed by malloc'd memory, outside the Java heap. See native_buffers.cpp. */
object NativeBuffers {
  init {
    System.loadLibrary("expodatagrid")
  }

  fun allocate(bytes: Long): ByteBuffer = nativeAllocate(bytes).order(ByteOrder.nativeOrder())

  /** The buffer must not be used after this call. */
  fun free(buffer: ByteBuffer) = nativeFree(buffer)

  @JvmStatic private external fun nativeAllocate(bytes: Long): ByteBuffer

  @JvmStatic private external fun nativeFree(buffer: ByteBuffer)
}

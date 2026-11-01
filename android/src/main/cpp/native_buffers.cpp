// Off-heap storage for grid columns.
//
// On Android, ByteBuffer.allocateDirect() is backed by a non-movable array on the Java heap,
// so 1M x 50 doubles (400 MB) would hit the per-app heap limit. These two functions allocate
// plain native memory and hand it to Kotlin as a direct ByteBuffer, which reads it without JNI calls.
#include <jni.h>
#include <cstdlib>
#include <cstring>

extern "C" JNIEXPORT jobject JNICALL
Java_expo_modules_datagrid_NativeBuffers_nativeAllocate(JNIEnv *env, jclass, jlong bytes) {
  if (bytes <= 0) bytes = 8;
  void *memory = std::calloc(1, static_cast<size_t>(bytes));
  if (memory == nullptr) {
    jclass oom = env->FindClass("java/lang/OutOfMemoryError");
    env->ThrowNew(oom, "expo-data-grid: native column allocation failed");
    return nullptr;
  }
  return env->NewDirectByteBuffer(memory, bytes);
}

extern "C" JNIEXPORT void JNICALL
Java_expo_modules_datagrid_NativeBuffers_nativeFree(JNIEnv *env, jclass, jobject buffer) {
  if (buffer == nullptr) return;
  void *memory = env->GetDirectBufferAddress(buffer);
  std::free(memory);
}

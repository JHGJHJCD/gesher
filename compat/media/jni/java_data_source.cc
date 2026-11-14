// Gesher: MediaDataSource's serialized callback and lifetime contract.
// Reference: Android 16 r4 JMediaDataSource (frameworks/base/media/jni).
#include "java_data_source.h"

#include <algorithm>
#include <limits>
#include <mutex>
#include <new>

#include "jni_util.h"

namespace gesher::media {
namespace {

class JavaDataSource final : public DataSource {
 public:
  bool Init(JNIEnv* env, jobject source) {
    if (env->ExceptionCheck()) return false;
    jclass cls = env->GetObjectClass(source);
    if (cls == nullptr) return false;
    read_at_ = env->GetMethodID(cls, "readAt", "(J[BII)I");
    if (read_at_ != nullptr) get_size_ = env->GetMethodID(cls, "getSize", "()J");
    if (get_size_ != nullptr) close_ = env->GetMethodID(cls, "close", "()V");
    env->DeleteLocalRef(cls);
    if (close_ == nullptr) return false;
    source_ = env->NewGlobalRef(source);
    if (source_ == nullptr) return false;
    jbyteArray local = env->NewByteArray(kBuffer);
    if (local == nullptr) return false;
    buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    initialized_ = buffer_ != nullptr;
    return initialized_;
  }

  ~JavaDataSource() override {
    JNIEnv* env = AttachedEnv("gesher-media");
    if (env == nullptr) return;
    // Cleanup may run while the caller is unwinding a failed setup. Calling
    // Java with its original exception pending is invalid JNI; retain that
    // exception across close, including a close callback that also throws.
    jthrowable pending = env->ExceptionOccurred();
    if (pending != nullptr) env->ExceptionClear();
    if (initialized_) {
      env->CallVoidMethod(source_, close_);
      // A normal close reports its Java exception to the JNI caller (notably
      // MediaMetadataRetriever.release). Cleanup must not replace an error
      // that was already pending before destruction.
      if (pending != nullptr) CheckException(env, "MediaDataSource.close");
    }
    if (buffer_ != nullptr) env->DeleteGlobalRef(buffer_);
    if (source_ != nullptr) env->DeleteGlobalRef(source_);
    if (pending != nullptr) {
      env->Throw(pending);
      env->DeleteLocalRef(pending);
    }
  }

  ssize_t ReadAt(int64_t offset, void* data, size_t size) override {
    std::lock_guard<std::mutex> lock(mu_);
    if (failed_) return -1;
    JNIEnv* env = AttachedEnv("gesher-media");
    if (env == nullptr || env->ExceptionCheck()) return -1;
    if (offset < 0 || size > static_cast<size_t>(std::numeric_limits<ssize_t>::max()) ||
        (size != 0 && data == nullptr)) return -1;
    size_t done = 0;
    // Match AOSP CallbackDataSource: accumulate valid short reads over the
    // bounded shared buffer until the requested count or an explicit EOF.
    while (done < size) {
      const jint requested = static_cast<jint>(std::min<size_t>(size - done, kBuffer));
      if (done > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() - offset)) {
        failed_ = true;
        return -1;
      }
      const jint n = env->CallIntMethod(source_, read_at_,
          static_cast<jlong>(offset + static_cast<int64_t>(done)), buffer_, 0, requested);
      if (CheckException(env, "MediaDataSource.readAt")) {
        failed_ = true;
        return -1;
      }
      if (n == -1) return static_cast<ssize_t>(done);  // EOF is not a failed source.
      if (n < -1 || n > requested) {
        failed_ = true;
        return -1;
      }
      if (n != 0) {
        env->GetByteArrayRegion(buffer_, 0, n,
            reinterpret_cast<jbyte*>(static_cast<uint8_t*>(data) + done));
        if (CheckException(env, "MediaDataSource buffer copy")) {
          failed_ = true;
          return -1;
        }
      }
      done += static_cast<size_t>(n);
      if (n == 0) break;
    }
    return static_cast<ssize_t>(done);
  }

  int64_t Size() override {
    std::lock_guard<std::mutex> lock(mu_);
    if (failed_) return -1;
    if (size_cached_) return size_;
    JNIEnv* env = AttachedEnv("gesher-media");
    if (env == nullptr || env->ExceptionCheck()) return -1;
    const jlong size = env->CallLongMethod(source_, get_size_);
    if (CheckException(env, "MediaDataSource.getSize")) {
      failed_ = true;
      return -1;
    }
    size_ = size < 0 ? -1 : size;
    size_cached_ = true;
    return size_;
  }

 private:
  static constexpr jint kBuffer = 64 * 1024;
  std::mutex mu_;
  jobject source_ = nullptr;
  jbyteArray buffer_ = nullptr;
  jmethodID read_at_ = nullptr, get_size_ = nullptr, close_ = nullptr;
  bool initialized_ = false, failed_ = false, size_cached_ = false;
  int64_t size_ = -1;
};

}  // namespace

std::unique_ptr<DataSource> OpenJavaDataSource(JNIEnv* env, jobject source) {
  if (env->ExceptionCheck()) return nullptr;
  if (source == nullptr) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) {
      env->ThrowNew(cls, "Null MediaDataSource");
      env->DeleteLocalRef(cls);
    }
    return nullptr;
  }
  auto result = std::unique_ptr<JavaDataSource>(new (std::nothrow) JavaDataSource);
  if (result == nullptr) {
    jclass cls = env->FindClass("java/lang/OutOfMemoryError");
    if (cls != nullptr) {
      env->ThrowNew(cls, "MediaDataSource adapter");
      env->DeleteLocalRef(cls);
    }
    return nullptr;
  }
  if (!result->Init(env, source)) return nullptr;
  return result;
}

}  // namespace gesher::media

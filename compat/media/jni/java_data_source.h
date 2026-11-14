// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
#pragma once

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>

#include "../player/data_source.h"
#include "jni_util.h"

namespace gesher::media {

void jniThrow(JNIEnv* env, const char* className, const char* message);

// One owned callback source shared by Player, Extractor and Retriever. The
// scratch Java array is bounded and is never used by concurrent callbacks.
class JavaDataSource final : public DataSource {
 public:
  static std::unique_ptr<DataSource> Create(JNIEnv* env, jobject source) {
    if (env->ExceptionCheck()) return nullptr;
    if (source == nullptr) {
      jniThrow(env, "java/lang/IllegalArgumentException", "null MediaDataSource");
      return nullptr;
    }
    std::unique_ptr<JavaDataSource> result(new (std::nothrow) JavaDataSource);
    if (result == nullptr) {
      jniThrow(env, "java/lang/OutOfMemoryError", "MediaDataSource adapter");
      return nullptr;
    }
    jclass cls = env->GetObjectClass(source);
    if (cls == nullptr) return nullptr;
    result->read_at_ = env->GetMethodID(cls, "readAt", "(J[BII)I");
    if (!env->ExceptionCheck()) result->get_size_ = env->GetMethodID(cls, "getSize", "()J");
    if (!env->ExceptionCheck()) result->close_ = env->GetMethodID(cls, "close", "()V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) return nullptr;
    jbyteArray buffer = env->NewByteArray(kBuffer);
    if (buffer == nullptr) return nullptr;
    result->buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(buffer));
    env->DeleteLocalRef(buffer);
    if (result->buffer_ == nullptr) return nullptr;
    result->source_ = env->NewGlobalRef(source);
    if (result->source_ == nullptr) return nullptr;
    return result;
  }

  ~JavaDataSource() override {
    JNIEnv* env = AttachedEnv("gesher-media");
    if (env == nullptr) return;
    // Closing a failed/replaced source must not erase an entrypoint's pending
    // exception, or invoke Java while it is pending (-Xcheck:jni also checks it).
    jthrowable pending = env->ExceptionOccurred();
    if (pending != nullptr) env->ExceptionClear();
    if (source_ != nullptr) {
      env->CallVoidMethod(source_, close_);
      CheckException(env, "MediaDataSource.close");
      env->DeleteGlobalRef(source_);
    }
    if (buffer_ != nullptr) env->DeleteGlobalRef(buffer_);
    if (pending != nullptr) {
      env->Throw(pending);
      env->DeleteLocalRef(pending);
    }
  }

  ssize_t ReadAt(int64_t offset, void* data, size_t size) override {
    if (offset < 0 || (size != 0 && data == nullptr) ||
        size > static_cast<size_t>(std::numeric_limits<ssize_t>::max()) ||
        size > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() - offset)) return -1;
    if (size == 0) return 0;
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    if (failed_) return -1;
    JNIEnv* env = AttachedEnv("gesher-media");
    if (env == nullptr || env->ExceptionCheck()) return -1;
    size_t done = 0;
    while (done < size) {
      jint requested = static_cast<jint>(std::min<size_t>(size - done, kBuffer));
      jint n = env->CallIntMethod(source_, read_at_, static_cast<jlong>(offset + done),
                                 buffer_, 0, requested);
      if (CheckException(env, "MediaDataSource.readAt")) { failed_ = true; return -1; }
      if (n == -1 || n == 0) break;  // Java EOF is -1; native EOF is zero.
      if (n < -1 || n > requested) { failed_ = true; return -1; }
      env->GetByteArrayRegion(buffer_, 0, n,
                             reinterpret_cast<jbyte*>(static_cast<uint8_t*>(data) + done));
      if (CheckException(env, "MediaDataSource buffer")) { failed_ = true; return -1; }
      done += static_cast<size_t>(n);
    }
    return static_cast<ssize_t>(done);
  }

  int64_t Size() override {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    if (failed_) return -1;
    if (size_cached_) return cached_size_;
    JNIEnv* env = AttachedEnv("gesher-media");
    if (env == nullptr || env->ExceptionCheck()) return -1;
    jlong size = env->CallLongMethod(source_, get_size_);
    if (CheckException(env, "MediaDataSource.getSize")) { failed_ = true; return -1; }
    cached_size_ = size < 0 ? -1 : size;
    size_cached_ = true;
    return cached_size_;
  }

 private:
  JavaDataSource() = default;
  static constexpr jint kBuffer = 64 * 1024;
  std::recursive_mutex mutex_;
  bool failed_ = false;
  bool size_cached_ = false;
  int64_t cached_size_ = -1;
  jobject source_ = nullptr;
  jbyteArray buffer_ = nullptr;
  jmethodID read_at_ = nullptr;
  jmethodID get_size_ = nullptr;
  jmethodID close_ = nullptr;
};

}  // namespace gesher::media

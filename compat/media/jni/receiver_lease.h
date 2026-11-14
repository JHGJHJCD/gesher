// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
#pragma once

#include <jni.h>

#include <atomic>
#include <mutex>
#include <new>
#include <utility>

namespace gesher::media {

void jniThrow(JNIEnv* env, const char* className, const char* message);

// The Java field owns one reference. An entrypoint retains another while holding
// its class's context lock, then takes the operation lock after dropping that
// lock. Release only detaches the field: callbacks can release their receiver
// without destroying an active parser, reader, or its MediaDataSource.
struct ReceiverState {
  std::atomic<unsigned> references{1};
  std::recursive_mutex operations;
};

template <class T>
class ReceiverLease {
 public:
  ReceiverLease() = default;
  ReceiverLease(std::nullptr_t) {}
  ReceiverLease(const ReceiverLease&) = delete;
  ReceiverLease& operator=(const ReceiverLease&) = delete;
  ReceiverLease(ReceiverLease&& other) noexcept
      : receiver_(std::exchange(other.receiver_, nullptr)), lock_(std::move(other.lock_)) {}
  ~ReceiverLease() {
    if (receiver_ != nullptr) {
      lock_.unlock();
      Drop(receiver_);
    }
  }
  T* operator->() const { return receiver_; }
  explicit operator bool() const { return receiver_ != nullptr; }
  bool operator==(std::nullptr_t) const { return receiver_ == nullptr; }
  bool operator!=(std::nullptr_t) const { return receiver_ != nullptr; }

  static ReceiverLease Acquire(JNIEnv* env, jobject thiz, jfieldID field, std::mutex& context) {
    T* receiver;
    {
      std::lock_guard<std::mutex> lock(context);
      receiver = reinterpret_cast<T*>(env->GetLongField(thiz, field));
      if (receiver != nullptr) receiver->references.fetch_add(1, std::memory_order_relaxed);
    }
    return ReceiverLease(receiver);
  }

  static void Setup(JNIEnv* env, jobject thiz, jfieldID field, std::mutex& context) {
    T* receiver = new (std::nothrow) T;
    if (receiver == nullptr) {
      jniThrow(env, "java/lang/OutOfMemoryError", "media receiver");
      return;
    }
    T* previous;
    {
      std::lock_guard<std::mutex> lock(context);
      previous = reinterpret_cast<T*>(env->GetLongField(thiz, field));
      env->SetLongField(thiz, field, reinterpret_cast<jlong>(receiver));
    }
    Drop(previous);
  }

  static void Release(JNIEnv* env, jobject thiz, jfieldID field, std::mutex& context) {
    T* receiver;
    {
      std::lock_guard<std::mutex> lock(context);
      receiver = reinterpret_cast<T*>(env->GetLongField(thiz, field));
      env->SetLongField(thiz, field, 0);
    }
    // A DataSource destructor calls Java close(); never do that under context.
    Drop(receiver);
  }

 private:
  explicit ReceiverLease(T* receiver) : receiver_(receiver) {
    if (receiver_ != nullptr) lock_ = std::unique_lock<std::recursive_mutex>(receiver_->operations);
  }
  static void Drop(T* receiver) {
    if (receiver != nullptr && receiver->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
      delete receiver;
    }
  }
  T* receiver_ = nullptr;
  std::unique_lock<std::recursive_mutex> lock_;
};

}  // namespace gesher::media

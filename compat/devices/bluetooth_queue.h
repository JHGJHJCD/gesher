// Gesher (devices track, docs/bluetooth.md): a bounded queue between the
// threads Windows calls Bluetooth event handlers on (or the test radio's) and
// the Java thread that waits for the next event.
#pragma once

#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>

namespace gesher::devices::bluetooth {

template <typename T>
class Queue {
 public:
  explicit Queue(size_t capacity) : capacity_(capacity) {}

  // Drops the oldest entry when full (a scan that nobody drains must not grow).
  void Push(T value) {
    {
      std::lock_guard<std::mutex> lock(mu_);
      if (closed_) return;
      if (items_.size() >= capacity_) items_.pop_front();
      items_.push_back(std::move(value));
    }
    cv_.notify_one();
  }

  // The next entry within timeoutMs (< 0: no limit); false on time-out or once
  // closed and drained.
  bool Pop(T* out, int timeoutMs) {
    std::unique_lock<std::mutex> lock(mu_);
    auto ready = [this] { return !items_.empty() || closed_; };
    if (timeoutMs < 0) {
      cv_.wait(lock, ready);
    } else if (!cv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), ready)) {
      return false;
    }
    if (items_.empty()) return false;
    *out = std::move(items_.front());
    items_.pop_front();
    return true;
  }

  void Close() {
    {
      std::lock_guard<std::mutex> lock(mu_);
      closed_ = true;
    }
    cv_.notify_all();
  }

  bool closed() {
    std::lock_guard<std::mutex> lock(mu_);
    return closed_;
  }

 private:
  const size_t capacity_;
  std::mutex mu_;
  std::condition_variable cv_;
  std::deque<T> items_;
  bool closed_ = false;
};

}  // namespace gesher::devices::bluetooth

// Shared checks for the production NDK entrypoints. The receiver records native
// window dispatch; it is not a color converter, compositor or display device.
#pragma once
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <initializer_list>

namespace native_window_dispatch_contract {
inline int checks = 0, failures = 0;
inline void Check(bool okay, const char* why) {
  ++checks;
  if (!okay) { ++failures; std::fprintf(stderr, "FAIL native window dispatch: %s\n", why); }
}

struct Receiver : ANativeWindow {
  int valid = 1;
  int producer_space = 142;
  int consumer_space = 245;
  int query_error = 0;
  int backend_error = 0;
  int validity_queries = 0, data_queries = 0, operations = 0;
  int operation = -1, first = -1, second = -1;
  double rate = -1;

  Receiver() { query = Query; perform = Perform; }
  static int Query(const ANativeWindow* window, int what, int* out) {
    auto* self = const_cast<Receiver*>(static_cast<const Receiver*>(window));
    if (what == NATIVE_WINDOW_IS_VALID) {
      ++self->validity_queries;
      *out = self->valid;
      return 0;
    }
    ++self->data_queries;
    if (self->query_error) return self->query_error;
    if (what == NATIVE_WINDOW_DATASPACE) *out = self->producer_space;
    else if (what == NATIVE_WINDOW_DEFAULT_DATASPACE) *out = self->consumer_space;
    else return -EINVAL;
    return 0;
  }
  static int Perform(ANativeWindow* window, int what, ...) {
    auto* self = static_cast<Receiver*>(window);
    ++self->operations;
    self->operation = what;
    va_list args;
    va_start(args, what);
    if (what == NATIVE_WINDOW_SET_FRAME_RATE) {
      self->rate = va_arg(args, double);
      self->first = va_arg(args, int);
      self->second = va_arg(args, int);
    } else {
      self->first = va_arg(args, int);
    }
    va_end(args);
    return self->backend_error;
  }
};

inline int Run(bool check_null = true) {
  Receiver receiver;
  Check(ANativeWindow_getBuffersDataSpace(&receiver) == 142,
        "current dataspace belongs to the producer");
  Check(ANativeWindow_getBuffersDefaultDataSpace(&receiver) == 245,
        "default dataspace belongs to the consumer");
  receiver.producer_space = 351;
  Check(ANativeWindow_getBuffersDefaultDataSpace(&receiver) == 245,
        "producer override does not change the consumer default");
  receiver.query_error = -EIO;
  Check(ANativeWindow_getBuffersDataSpace(&receiver) == -EIO,
        "current dataspace query error propagates");
  Check(ANativeWindow_getBuffersDefaultDataSpace(&receiver) == -EIO,
        "default dataspace query error propagates");
  receiver.query_error = 0;

  // Every admitted combination, including the camera's inverse-display bit.
  for (int transform = 0; transform < 16; ++transform) {
    int before = receiver.operations;
    Check(ANativeWindow_setBuffersTransform(&receiver, transform) == 0,
          "legal transform mask is accepted");
    Check(receiver.operations == before + 1 &&
          receiver.operation == NATIVE_WINDOW_SET_BUFFERS_TRANSFORM && receiver.first == transform,
          "legal transform is forwarded unchanged once");
  }
  for (int transform : {16, 32, -1, INT32_MIN}) {
    int before = receiver.operations;
    Check(ANativeWindow_setBuffersTransform(&receiver, transform) == -EINVAL,
          "unknown transform bits are rejected");
    Check(receiver.operations == before, "rejected transform does not reach the backend");
  }
  Check(ANativeWindow_setBuffersDataSpace(&receiver, 567) == 0 &&
        receiver.operation == NATIVE_WINDOW_SET_BUFFERS_DATASPACE && receiver.first == 567,
        "dataspace is forwarded to the receiver");
  Check(ANativeWindow_setFrameRate(&receiver, 24.0f, 1) == 0 &&
        receiver.operation == NATIVE_WINDOW_SET_FRAME_RATE && receiver.rate == 24.0 &&
        receiver.first == 1 && receiver.second == ANATIVEWINDOW_CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
        "ordinary frame-rate request uses the seamless strategy");
  Check(ANativeWindow_setFrameRateWithChangeStrategy(&receiver, 30.0f, 0, 1) == 0 &&
        receiver.rate == 30.0 && receiver.first == 0 && receiver.second == 1,
        "explicit frame-rate strategy is forwarded without narrowing");
  receiver.backend_error = -ENOSYS;
  Check(ANativeWindow_setBuffersTransform(&receiver, 0) == -ENOSYS,
        "transform backend error propagates");
  Check(ANativeWindow_setBuffersDataSpace(&receiver, 0) == -ENOSYS,
        "dataspace backend error propagates");
  Check(ANativeWindow_setFrameRate(&receiver, 0, 0) == -ENOSYS,
        "frame-rate backend error propagates");
  Check(ANativeWindow_setFrameRateWithChangeStrategy(&receiver, 0, 0, 0) == -ENOSYS,
        "explicit frame-rate backend error propagates");

  receiver.valid = 0;
  receiver.backend_error = 0;
  const int before = receiver.operations, queries = receiver.data_queries;
  Check(ANativeWindow_setBuffersTransform(&receiver, 0) == -EINVAL, "invalid window rejects transform");
  Check(ANativeWindow_setBuffersDataSpace(&receiver, 0) == -EINVAL, "invalid window rejects dataspace");
  Check(ANativeWindow_getBuffersDataSpace(&receiver) == -EINVAL, "invalid window rejects current query");
  Check(ANativeWindow_getBuffersDefaultDataSpace(&receiver) == -EINVAL, "invalid window rejects default query");
  Check(ANativeWindow_setFrameRate(&receiver, 0, 0) == -EINVAL, "invalid window rejects frame rate");
  Check(ANativeWindow_setFrameRateWithChangeStrategy(&receiver, 0, 0, 0) == -EINVAL,
        "invalid window rejects explicit frame rate");
  Check(receiver.operations == before && receiver.data_queries == queries,
        "invalid window stops before data query or mutation");

  if (check_null) {
    // The NDK annotates these inputs _Nonnull. Read a runtime null value to test
    // the pinned rejection without a -Wnonnull diagnostic for a literal.
    ANativeWindow* volatile null_window = nullptr;
    Check(ANativeWindow_setBuffersTransform(null_window, 0) == -EINVAL, "null window rejects transform");
    Check(ANativeWindow_setBuffersDataSpace(null_window, 0) == -EINVAL, "null window rejects dataspace");
    Check(ANativeWindow_getBuffersDataSpace(null_window) == -EINVAL, "null window rejects current query");
    Check(ANativeWindow_getBuffersDefaultDataSpace(null_window) == -EINVAL, "null window rejects default query");
    Check(ANativeWindow_setFrameRate(null_window, 0, 0) == -EINVAL, "null window rejects frame rate");
    Check(ANativeWindow_setFrameRateWithChangeStrategy(null_window, 0, 0, 0) == -EINVAL,
          "null window rejects explicit frame rate");
  }
  std::printf("native window dispatch contracts: %d checks, %d failures\n", checks, failures);
  return failures;
}
}  // namespace native_window_dispatch_contract

// Gesher: the NDK's ANativeWindow functions hostgraphics lacks, and
// gesher_window_* (gesher_native_window.h). As libnativewindow implements
// them on a device: perform() on whatever ANativeWindow they are given.
#include "gesher_native_window.h"

#include <errno.h>

#include <system/window.h>

#include "gesher_window.h"
#include "win32_surface.h"

using gesher::Win32Surface;

namespace {

int32_t WindowQuery(ANativeWindow* window, int what) {
  int value = 0;
  int err = window->query(window, what, &value);
  return err < 0 ? err : value;
}

// Keep the admission check used by the pinned libnativewindow entrypoints.
// Geometry/lock retain their separate, older NDK contracts below.
bool ValidWindow(ANativeWindow* window) {
  return window != nullptr && WindowQuery(window, NATIVE_WINDOW_IS_VALID) != 0;
}

}  // namespace

extern "C" {

int32_t ANativeWindow_lock(ANativeWindow* window, ANativeWindow_Buffer* out_buffer,
                           ARect* in_out_dirty_bounds) {
  return window->perform(window, NATIVE_WINDOW_LOCK, out_buffer, in_out_dirty_bounds);
}

int32_t ANativeWindow_unlockAndPost(ANativeWindow* window) {
  return window->perform(window, NATIVE_WINDOW_UNLOCK_AND_POST);
}

int32_t ANativeWindow_setBuffersGeometry(ANativeWindow* window, int32_t width, int32_t height,
                                         int32_t format) {
  int err = native_window_set_buffers_format(window, format);
  if (err == 0) {
    // 0 x 0: the window's size again.
    err = native_window_set_buffers_user_dimensions(window, width, height);
  }
  return err;
}

int32_t ANativeWindow_setBuffersTransform(ANativeWindow* window, int32_t transform) {
  constexpr int32_t all_transforms = NATIVE_WINDOW_TRANSFORM_FLIP_H |
      NATIVE_WINDOW_TRANSFORM_FLIP_V | NATIVE_WINDOW_TRANSFORM_ROT_90 |
      NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY;
  if (!ValidWindow(window) || (transform & ~all_transforms) != 0) return -EINVAL;
  return native_window_set_buffers_transform(window, transform);
}

int32_t ANativeWindow_setBuffersDataSpace(ANativeWindow* window, int32_t data_space) {
  if (!ValidWindow(window)) return -EINVAL;
  return native_window_set_buffers_data_space(window, static_cast<android_dataspace_t>(data_space));
}

int32_t ANativeWindow_getBuffersDataSpace(ANativeWindow* window) {
  if (!ValidWindow(window)) return -EINVAL;
  return WindowQuery(window, NATIVE_WINDOW_DATASPACE);
}

int32_t ANativeWindow_getBuffersDefaultDataSpace(ANativeWindow* window) {
  if (!ValidWindow(window)) return -EINVAL;
  return WindowQuery(window, NATIVE_WINDOW_DEFAULT_DATASPACE);
}

int32_t ANativeWindow_setFrameRate(ANativeWindow* window, float frame_rate, int8_t compatibility) {
  return ANativeWindow_setFrameRateWithChangeStrategy(
      window, frame_rate, compatibility, ANATIVEWINDOW_CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
}

int32_t ANativeWindow_setFrameRateWithChangeStrategy(ANativeWindow* window, float frame_rate,
                                                     int8_t compatibility,
                                                     int8_t change_frame_rate_strategy) {
  if (!ValidWindow(window)) return -EINVAL;
  return window->perform(window, NATIVE_WINDOW_SET_FRAME_RATE, static_cast<double>(frame_rate),
                         static_cast<int>(compatibility),
                         static_cast<int>(change_frame_rate_strategy));
}

// ANativeWindow_getNextFrameId and the query interceptor are inline in
// <system/window.h>; tryAllocateBuffers and the rest are hostgraphics'.

// ANativeWindow_toSurface is with the JNI code (window_jni.cc).

ANativeWindow* gesher_window_create(const char* title, int32_t width, int32_t height) {
  gesher::Window* window = gesher::Window::Create(title != nullptr ? title : "", width, height);
  if (window == nullptr) return nullptr;
  auto* surface = new Win32Surface(window);
  ANativeWindow* anw = surface;
  ANativeWindow_acquire(anw);
  return anw;
}

void gesher_window_show(ANativeWindow* window) {
  if (Win32Surface* surface = Win32Surface::From(window)) surface->window()->Show();
}

void* gesher_window_hwnd(ANativeWindow* window) {
  Win32Surface* surface = Win32Surface::From(window);
  return surface != nullptr ? surface->window()->hwnd() : nullptr;
}

int gesher_window_translucent(ANativeWindow* window) {
  Win32Surface* surface = Win32Surface::From(window);
  return surface != nullptr && surface->window()->translucent() ? 1 : 0;
}

void gesher_window_client_size(ANativeWindow* window, int32_t* width, int32_t* height) {
  Win32Surface* surface = Win32Surface::From(window);
  *width = surface != nullptr ? surface->window()->width() : 0;
  *height = surface != nullptr ? surface->window()->height() : 0;
}

void gesher_window_buffer_size(ANativeWindow* window, int32_t* width, int32_t* height) {
  Win32Surface* surface = Win32Surface::From(window);
  if (surface != nullptr) surface->BufferSize(width, height);
  else { *width = 0; *height = 0; }
}

int gesher_window_has_surface_insets(ANativeWindow* window) {
  Win32Surface* surface = Win32Surface::From(window);
  return surface != nullptr && surface->HasSurfaceInsets();
}

void gesher_window_present_pixels(ANativeWindow* window, const uint32_t* argb, int32_t width,
                                  int32_t height) {
  if (Win32Surface* surface = Win32Surface::From(window)) {
    surface->PresentPixels(argb, width, height);
  }
}

}  // extern "C"

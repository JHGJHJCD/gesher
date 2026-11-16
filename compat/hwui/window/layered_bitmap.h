// A layered window's 32-bit, top-down DIB. Used and destroyed on its owner
// thread only; dimensions are physical pixels, independent of window DPI.
#pragma once

#include <windows.h>
#include <cstdint>
#include <cstring>

namespace gesher {

class LayeredBitmap {
 public:
  LayeredBitmap() = default;
  ~LayeredBitmap() { Reset(); }
  LayeredBitmap(const LayeredBitmap&) = delete;
  LayeredBitmap& operator=(const LayeredBitmap&) = delete;

  bool Copy(const uint8_t* source, int width, int height) {
    if (source == nullptr || width <= 0 || height <= 0) return false;
    // Finish any GDI use before modifying or replacing the selected DIB.
    GdiFlush();
    if ((width != width_ || height != height_) && !Resize(width, height)) return false;
    std::memcpy(bits_, source, static_cast<size_t>(width_) * height_ * 4);
    return true;
  }

  HDC dc() const { return dc_; }

  void Reset() {
    if (dc_ != nullptr) {
      GdiFlush();
      SelectObject(dc_, original_);
      DeleteObject(bitmap_);
      DeleteDC(dc_);
    }
    dc_ = nullptr;
    bitmap_ = nullptr;
    original_ = nullptr;
    bits_ = nullptr;
    width_ = height_ = 0;
  }

 private:
  bool Resize(int width, int height) {
    HDC dc = dc_ != nullptr ? dc_ : CreateCompatibleDC(nullptr);
    if (dc == nullptr) return false;
    BITMAPINFO info = {};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void* bits = nullptr;
    HBITMAP bitmap = CreateDIBSection(dc, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (bitmap == nullptr || bits == nullptr) {
      if (bitmap != nullptr) DeleteObject(bitmap);
      if (dc_ == nullptr) DeleteDC(dc);
      return false;
    }
    HGDIOBJ previous = SelectObject(dc, bitmap);
    if (previous == nullptr || previous == HGDI_ERROR) {
      DeleteObject(bitmap);
      if (dc_ == nullptr) DeleteDC(dc);
      return false;
    }
    if (dc_ == nullptr) {
      dc_ = dc;
      original_ = previous;
    } else {
      DeleteObject(bitmap_);  // now deselected, safe to retire
    }
    bitmap_ = bitmap;
    bits_ = bits;
    width_ = width;
    height_ = height;
    return true;
  }

  HDC dc_ = nullptr;
  HBITMAP bitmap_ = nullptr;
  HGDIOBJ original_ = nullptr;
  void* bits_ = nullptr;
  int width_ = 0, height_ = 0;
};

}  // namespace gesher

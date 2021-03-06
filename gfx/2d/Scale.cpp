/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "Scale.h"

#ifdef USE_SKIA
#include "HelpersSkia.h"
#include "skia/SkBitmap.h"
#include "image_operations.h"
#endif

namespace mozilla {
namespace gfx {

bool Scale(uint8_t* srcData, int32_t srcWidth, int32_t srcHeight, int32_t srcStride,
           uint8_t* dstData, int32_t dstWidth, int32_t dstHeight, int32_t dstStride,
           SurfaceFormat format)
{
#ifdef USE_SKIA
  bool opaque;
  if (format == FORMAT_B8G8R8A8) {
    opaque = false;
  } else {
    opaque = true;
  }

  SkBitmap::Config config = GfxFormatToSkiaConfig(format);

  SkBitmap imgSrc;
  imgSrc.setConfig(config, srcWidth, srcHeight, srcStride);
  imgSrc.setPixels(srcData);
  imgSrc.setIsOpaque(opaque);

  // Rescaler is compatible with 32 bpp only. Convert to RGB32 if needed.
  if (config != SkBitmap::kARGB_8888_Config) {
    imgSrc.copyTo(&imgSrc, SkBitmap::kARGB_8888_Config);
  }

  // This returns an SkBitmap backed by dstData; since it also wrote to dstData,
  // we don't need to look at that SkBitmap.
  SkBitmap result = skia::ImageOperations::Resize(imgSrc,
                                                  skia::ImageOperations::RESIZE_BEST,
                                                  dstWidth, dstHeight,
                                                  dstData);

  return !result.isNull();
#else
  return false;
#endif
}

}
}

/* -*- Mode: C++; tab-width: 2; indent-tabs-mode: nil; c-basic-offset: 2 -*-*/
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "ImageConversion.h"

#include "ImageContainer.h"
#include "libyuv/convert.h"
#include "libyuv/convert_from_argb.h"
#include "mozilla/dom/ImageBitmapBinding.h"
#include "mozilla/dom/ImageUtils.h"
#include "mozilla/gfx/Point.h"
#include "mozilla/RefPtr.h"
#include "mozilla/Result.h"
#include "nsThreadUtils.h"

using mozilla::ImageFormat;
using mozilla::dom::ImageBitmapFormat;
using mozilla::dom::ImageUtils;
using mozilla::gfx::DataSourceSurface;
using mozilla::gfx::SourceSurface;
using mozilla::gfx::SurfaceFormat;
using mozilla::layers::Image;
using mozilla::layers::PlanarYCbCrData;
using mozilla::layers::PlanarYCbCrImage;

static const PlanarYCbCrData* GetPlanarYCbCrData(Image* aImage) {
  switch (aImage->GetFormat()) {
    case ImageFormat::PLANAR_YCBCR:
      return aImage->AsPlanarYCbCrImage()->GetData();
    case ImageFormat::NV_IMAGE:
      return aImage->AsNVImage()->GetData();
    default:
      return nullptr;
  }
}

static nsresult MapRv(int aRv) {
  // Docs for libyuv::ConvertToI420 say:
  // Returns 0 for successful; -1 for invalid parameter. Non-zero for failure.
  switch (aRv) {
    case 0:
      return NS_OK;
    case -1:
      return NS_ERROR_INVALID_ARG;
    default:
      return NS_ERROR_FAILURE;
  }
}

namespace mozilla {

already_AddRefed<SourceSurface> GetSourceSurface(Image* aImage) {
  if (!aImage->AsGLImage() || NS_IsMainThread()) {
    return aImage->GetAsSourceSurface();
  }

  // GLImage::GetAsSourceSurface() only supports main thread
  RefPtr<SourceSurface> surf;
  NS_DispatchAndSpinEventLoopUntilComplete(
      "ImageToI420::GLImage::GetSourceSurface"_ns,
      mozilla::GetMainThreadSerialEventTarget(),
      NS_NewRunnableFunction(
          "ImageToI420::GLImage::GetSourceSurface",
          [&aImage, &surf]() { surf = aImage->GetAsSourceSurface(); }));

  return surf.forget();
}

nsresult ConvertToI420(Image* aImage, uint8_t* aDestY, int aDestStrideY,
                       uint8_t* aDestU, int aDestStrideU, uint8_t* aDestV,
                       int aDestStrideV) {
  if (!aImage->IsValid()) {
    return NS_ERROR_INVALID_ARG;
  }

  if (const PlanarYCbCrData* data = GetPlanarYCbCrData(aImage)) {
    const ImageUtils imageUtils(aImage);
    Maybe<dom::ImageBitmapFormat> format = imageUtils.GetFormat();
    if (format.isNothing()) {
      MOZ_ASSERT_UNREACHABLE("YUV format conversion not implemented");
      return NS_ERROR_NOT_IMPLEMENTED;
    }
    switch (format.value()) {
      case ImageBitmapFormat::YUV420P:
        return MapRv(libyuv::I420ToI420(
            data->mYChannel, data->mYStride, data->mCbChannel,
            data->mCbCrStride, data->mCrChannel, data->mCbCrStride, aDestY,
            aDestStrideY, aDestU, aDestStrideU, aDestV, aDestStrideV,
            aImage->GetSize().width, aImage->GetSize().height));
      case ImageBitmapFormat::YUV422P:
        return MapRv(libyuv::I422ToI420(
            data->mYChannel, data->mYStride, data->mCbChannel,
            data->mCbCrStride, data->mCrChannel, data->mCbCrStride, aDestY,
            aDestStrideY, aDestU, aDestStrideU, aDestV, aDestStrideV,
            aImage->GetSize().width, aImage->GetSize().height));
      case ImageBitmapFormat::YUV444P:
        return MapRv(libyuv::I444ToI420(
            data->mYChannel, data->mYStride, data->mCbChannel,
            data->mCbCrStride, data->mCrChannel, data->mCbCrStride, aDestY,
            aDestStrideY, aDestU, aDestStrideU, aDestV, aDestStrideV,
            aImage->GetSize().width, aImage->GetSize().height));
      case ImageBitmapFormat::YUV420SP_NV12:
        return MapRv(libyuv::NV12ToI420(
            data->mYChannel, data->mYStride, data->mCbChannel,
            data->mCbCrStride, aDestY, aDestStrideY, aDestU, aDestStrideU,
            aDestV, aDestStrideV, aImage->GetSize().width,
            aImage->GetSize().height));
      case ImageBitmapFormat::YUV420SP_NV21:
        return MapRv(libyuv::NV21ToI420(
            data->mYChannel, data->mYStride, data->mCrChannel,
            data->mCbCrStride, aDestY, aDestStrideY, aDestU, aDestStrideU,
            aDestV, aDestStrideV, aImage->GetSize().width,
            aImage->GetSize().height));
      default:
        MOZ_ASSERT_UNREACHABLE("YUV format conversion not implemented");
        return NS_ERROR_NOT_IMPLEMENTED;
    }
  }

  RefPtr<SourceSurface> surf = GetSourceSurface(aImage);
  if (!surf) {
    return NS_ERROR_FAILURE;
  }

  RefPtr<DataSourceSurface> data = surf->GetDataSurface();
  if (!data) {
    return NS_ERROR_FAILURE;
  }

  DataSourceSurface::ScopedMap map(data, DataSourceSurface::READ);
  if (!map.IsMapped()) {
    return NS_ERROR_FAILURE;
  }

  switch (surf->GetFormat()) {
    case SurfaceFormat::B8G8R8A8:
    case SurfaceFormat::B8G8R8X8:
      return MapRv(libyuv::ARGBToI420(
          static_cast<uint8_t*>(map.GetData()), map.GetStride(), aDestY,
          aDestStrideY, aDestU, aDestStrideU, aDestV, aDestStrideV,
          aImage->GetSize().width, aImage->GetSize().height));
    case SurfaceFormat::R5G6B5_UINT16:
      return MapRv(libyuv::RGB565ToI420(
          static_cast<uint8_t*>(map.GetData()), map.GetStride(), aDestY,
          aDestStrideY, aDestU, aDestStrideU, aDestV, aDestStrideV,
          aImage->GetSize().width, aImage->GetSize().height));
    default:
      MOZ_ASSERT_UNREACHABLE("Surface format conversion not implemented");
      return NS_ERROR_NOT_IMPLEMENTED;
  }
}

nsresult ConvertToNV12(layers::Image* aImage, uint8_t* aDestY, int aDestStrideY,
                       uint8_t* aDestUV, int aDestStrideUV) {
  if (!aImage->IsValid()) {
    return NS_ERROR_INVALID_ARG;
  }

  if (const PlanarYCbCrData* data = GetPlanarYCbCrData(aImage)) {
    const ImageUtils imageUtils(aImage);
    Maybe<dom::ImageBitmapFormat> format = imageUtils.GetFormat();
    if (format.isNothing()) {
      MOZ_ASSERT_UNREACHABLE("YUV format conversion not implemented");
      return NS_ERROR_NOT_IMPLEMENTED;
    }

    if (format.value() != ImageBitmapFormat::YUV420P) {
      NS_WARNING("ConvertToNV12: Convert YUV data in I420 only");
      return NS_ERROR_NOT_IMPLEMENTED;
    }

    return MapRv(libyuv::I420ToNV12(
        data->mYChannel, data->mYStride, data->mCbChannel, data->mCbCrStride,
        data->mCrChannel, data->mCbCrStride, aDestY, aDestStrideY, aDestUV,
        aDestStrideUV, aImage->GetSize().width, aImage->GetSize().height));
  }

  RefPtr<SourceSurface> surf = GetSourceSurface(aImage);
  if (!surf) {
    return NS_ERROR_FAILURE;
  }

  RefPtr<DataSourceSurface> data = surf->GetDataSurface();
  if (!data) {
    return NS_ERROR_FAILURE;
  }

  DataSourceSurface::ScopedMap map(data, DataSourceSurface::READ);
  if (!map.IsMapped()) {
    return NS_ERROR_FAILURE;
  }

  if (surf->GetFormat() != SurfaceFormat::B8G8R8A8 &&
      surf->GetFormat() != SurfaceFormat::B8G8R8X8) {
    NS_WARNING("ConvertToNV12: Convert SurfaceFormat in BGR* only");
    return NS_ERROR_NOT_IMPLEMENTED;
  }

  return MapRv(
      libyuv::ARGBToNV12(static_cast<uint8_t*>(map.GetData()), map.GetStride(),
                         aDestY, aDestStrideY, aDestUV, aDestStrideUV,
                         aImage->GetSize().width, aImage->GetSize().height));
}

}  // namespace mozilla

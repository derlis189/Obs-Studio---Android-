// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstdint>
#include <cstddef>

void android_uvc_deliver_yuy2(const uint8_t *data, uint32_t width, uint32_t height, uint64_t timestamp_ns);
void android_uvc_deliver_nv12(const uint8_t *y, const uint8_t *uv, uint32_t stride,
                              uint32_t width, uint32_t height, uint64_t timestamp_ns);
void android_uvc_deliver_i420(const uint8_t *y, const uint8_t *u, const uint8_t *v,
                              uint32_t y_stride, uint32_t u_stride, uint32_t v_stride,
                              uint32_t width, uint32_t height, uint64_t timestamp_ns);
void android_uvc_stop_all();

// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif
void android_ndi_register();
void android_ndi_shutdown();
bool obs_android_ndi_output_start(const char *name);
void obs_android_ndi_output_stop();
bool obs_android_ndi_output_status(char *out, size_t capacity);
#ifdef __cplusplus
}
#endif

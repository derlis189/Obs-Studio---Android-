// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstddef>

void android_multistream_shutdown();
void android_vertical_register();

extern "C" {
bool obs_android_multistream_start_target(const char *id, const char *server, const char *key, int bitrate_kbps);
bool obs_android_multistream_start_target_ex(
    const char *id, const char *server, const char *key, int bitrate_kbps,
    bool use_vertical, int vert_w, int vert_h, const char *vert_scene, int fit_mode);
bool obs_android_multistream_stop_target(const char *id);
bool obs_android_multistream_is_active(const char *id);
bool obs_android_multistream_is_reconnecting(const char *id);

bool obs_android_vertical_start_stream(
    const char *server, const char *key, int width, int height, int bitrate_kbps,
    const char *scene_name, int fit_mode);
bool obs_android_vertical_stop_stream();
bool obs_android_vertical_is_streaming();
bool obs_android_vertical_is_reconnecting();

bool obs_android_vertical_start_record(
    const char *output_file_path, int width, int height, int bitrate_kbps,
    const char *scene_name, int fit_mode);
bool obs_android_vertical_stop_record();
bool obs_android_vertical_is_recording();

bool obs_android_vertical_start_backtrack(
    int width, int height, int bitrate_kbps, const char *scene_name, int fit_mode, int seconds);
bool obs_android_vertical_stop_backtrack();
bool obs_android_vertical_is_backtrack_active();
bool obs_android_vertical_save_backtrack(const char *output_file_path);
bool obs_android_vertical_get_stats(char *out_buf, size_t out_cap);

void obs_android_vertical_set_scene(const char *scene_name, int fit_mode);
void obs_android_vertical_set_background(const char *background);
void obs_android_vertical_set_params(int crop_x, int pos_y, int blur_amount);
void obs_android_vertical_set_transform(int crop_x, int pos_x, int pos_y, int scale_pct, int blur_amount);
}

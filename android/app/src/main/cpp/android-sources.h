// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <obs.h>
void android_captures_register();
void android_captures_initialize(obs_scene_t *first, obs_scene_t *second);
void android_captures_shutdown();
void android_captures_add_scene(obs_scene_t *scene);
void android_captures_remove_scene(obs_scene_t *scene);
obs_data_t *android_capture_stats();
float android_sources_get_mic_level();

/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <obs.h>
#include <util/platform-android.h>
#include <util/platform.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define REQUIRE(condition) do { \
    if (!(condition)) { fprintf(stderr, "CORE_TEST_FAIL line=%d: %s\n", __LINE__, #condition); exit(1); } \
} while (0)

int main(int argc, char **argv)
{
    REQUIRE(argc == 4);
    REQUIRE(!obs_android_set_paths("relative", argv[2], argv[3]));
    REQUIRE(obs_android_set_paths(argv[1], argv[2], argv[3]));
    REQUIRE(!obs_initialized());
    for (unsigned int cycle = 0; cycle < 3; ++cycle) {
        REQUIRE(obs_startup("en-US", argv[1], NULL));
        REQUIRE(obs_initialized());
        struct obs_audio_info audio = {.samples_per_sec = 48000, .speakers = SPEAKERS_STEREO};
        REQUIRE(obs_reset_audio(&audio));
        struct obs_audio_info actual_audio;
        REQUIRE(obs_get_audio_info(&actual_audio));
        REQUIRE(actual_audio.samples_per_sec == 48000 && actual_audio.speakers == SPEAKERS_STEREO);
        struct obs_video_info video;
        REQUIRE(!obs_get_video_info(&video));
        obs_scene_t *program = obs_scene_create("Program core test");
        obs_scene_t *child = obs_scene_create("Nested scene core test");
        REQUIRE(program && child);
        obs_source_t *child_source = obs_scene_get_source(child);
        const char *uuid = obs_source_get_uuid(child_source);
        REQUIRE(uuid && strlen(uuid) == 36 && uuid[14] == '4');
        obs_source_t *lookup = obs_get_source_by_uuid(uuid);
        REQUIRE(lookup == child_source);
        obs_source_release(lookup);
        obs_sceneitem_t *item = obs_scene_add(program, child_source);
        REQUIRE(item && obs_sceneitem_get_source(item) == child_source);
        REQUIRE(obs_scene_find_source(program, "Nested scene core test") == item);
        obs_source_set_volume(child_source, 0.35f);
        REQUIRE(fabsf(obs_source_get_volume(child_source) - 0.35f) < 0.0001f);
        obs_source_set_muted(child_source, true);
        REQUIRE(obs_source_muted(child_source));
        obs_data_t *saved = obs_save_source(child_source);
        REQUIRE(saved);
        obs_data_t *roundtrip = obs_data_create_from_json(obs_data_get_json(saved));
        REQUIRE(roundtrip && strcmp(obs_data_get_string(roundtrip, "id"), "scene") == 0);
        REQUIRE(strcmp(obs_data_get_string(roundtrip, "uuid"), uuid) == 0);
        obs_data_release(roundtrip);
        obs_data_release(saved);
        obs_sceneitem_remove(item);
        REQUIRE(!obs_scene_find_source(program, "Nested scene core test"));
        obs_scene_release(child);
        obs_scene_release(program);
        obs_shutdown();
        REQUIRE(!obs_initialized());
        printf("CORE_CYCLE_PASS %u\n", cycle + 1);
    }
    puts("CORE_TEST_PASS cycles=3 scene_graph=real_obs audio_engine=48000_stereo persistence=json_roundtrip graphics=not_initialized");
    return 0;
}

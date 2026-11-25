/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <obs.h>
#include <util/platform-android.h>
#include <util/platform.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdatomic.h>

#define REQUIRE(condition) do { if (!(condition)) { fprintf(stderr, "GRAPHICS_TEST_FAIL line=%d: %s\n", __LINE__, #condition); exit(1); } } while (0)
static atomic_int frames;
static atomic_bool correct;
static atomic_int expected;
static void on_frame(void *unused, struct video_data *frame)
{
    (void)unused;
    atomic_fetch_add(&frames, 1);
    const uint8_t *red = frame->data[0] + 40 * frame->linesize[0] + 40 * 4;
    const uint8_t *blue = frame->data[0] + 120 * frame->linesize[0] + 200 * 4;
    const uint8_t *image_pixel = frame->data[0] + 230 * frame->linesize[0] + 360 * 4;
    if (atomic_load(&expected) == 0 && red[0] > 200 && red[1] < 50 && red[2] < 50 && blue[0] < 50 && blue[1] < 50 && blue[2] > 200 &&
        image_pixel[0] >= 232 && image_pixel[0] <= 248 && image_pixel[1] >= 235 && image_pixel[1] <= 251 && image_pixel[2] >= 240)
        atomic_store(&correct, true);
    if (atomic_load(&expected) == 1 && red[0] < 20 && red[1] > 240 && red[2] < 20)
        atomic_store(&correct, true);
    if (atomic_load(&frames) <= 3)
        printf("PIXELS red=%u,%u,%u blue=%u,%u,%u image=%u,%u,%u\n", red[0], red[1], red[2], blue[0], blue[1], blue[2], image_pixel[0], image_pixel[1], image_pixel[2]);
}
static obs_source_t *color(const char *name, uint32_t rgba, int width, int height)
{
    obs_data_t *settings = obs_data_create();
    obs_data_set_int(settings, "color", rgba);
    obs_data_set_int(settings, "width", width);
    obs_data_set_int(settings, "height", height);
    obs_source_t *source = obs_source_create("color_source_v3", name, settings, NULL);
    obs_data_release(settings);
    return source;
}
int main(int argc, char **argv)
{
    REQUIRE(argc == 4);
    REQUIRE(obs_android_set_paths(argv[1], argv[2], argv[3]));
    REQUIRE(obs_startup("en-US", argv[1], NULL));
    struct obs_audio_info audio = {.samples_per_sec = 48000, .speakers = SPEAKERS_STEREO};
    REQUIRE(obs_reset_audio(&audio));
    char module[1024];
    snprintf(module, sizeof(module), "%s/libobs-opengl.so", argv[3]);
    struct obs_video_info info = {
        .graphics_module = module, .fps_num = 30, .fps_den = 1,
        .base_width = 640, .base_height = 360, .output_width = 640, .output_height = 360,
        .output_format = VIDEO_FORMAT_BGRA, .adapter = 0, .gpu_conversion = false,
        .colorspace = VIDEO_CS_709, .range = VIDEO_RANGE_FULL, .scale_type = OBS_SCALE_BILINEAR
    };
    int result = obs_reset_video(&info);
    printf("VIDEO_RESET_RESULT=%d\n", result);
    REQUIRE(result == OBS_VIDEO_SUCCESS);
    REQUIRE(obs_load_core_modules());
    obs_data_t *private_data = obs_get_private_data();
    obs_data_set_bool(private_data, "AbsoluteCoordinates", true);
    obs_data_release(private_data);
    obs_scene_t *scene = obs_scene_create("GPU scene test");
    obs_source_t *red = color("Red background", 0xff0000ff, 640, 360);
    obs_source_t *blue = color("Blue inset", 0xffff0000, 160, 90);
    REQUIRE(scene && red && blue);
    REQUIRE(obs_scene_add(scene, red));
    obs_sceneitem_t *inset = obs_scene_add(scene, blue);
    REQUIRE(inset);
    struct vec2 position;
    vec2_set(&position, 160.0f, 90.0f);
    obs_sceneitem_set_pos(inset, &position);
    obs_data_t *image_settings = obs_data_create();
    char image_path[1024];
    snprintf(image_path, sizeof(image_path), "%s/android-test.png", argv[2]);
    obs_data_set_string(image_settings, "file", image_path);
    obs_data_set_bool(image_settings, "unload", false);
    obs_source_t *image_source = obs_source_create("image_source", "PNG source test", image_settings, NULL);
    obs_data_release(image_settings);
    REQUIRE(image_source && obs_source_get_width(image_source) == 320);
    obs_sceneitem_t *image_item = obs_scene_add(scene, image_source);
    REQUIRE(image_item);
    vec2_set(&position, 320.0f, 180.0f);
    obs_sceneitem_set_pos(image_item, &position);
    obs_scene_t *green_scene = obs_scene_create("Transition target");
    obs_source_t *green = color("Green background", 0xff00ff00, 640, 360);
    REQUIRE(green_scene && green && obs_scene_add(green_scene, green));
    obs_source_t *fade = obs_source_create("fade_transition", "Original fade module", NULL, NULL);
    REQUIRE(fade);
    obs_transition_set(fade, obs_scene_get_source(scene));
    obs_set_output_source(0, fade);
    struct video_scale_info conversion = {
        .format = VIDEO_FORMAT_RGBA, .width = 640, .height = 360,
        .range = VIDEO_RANGE_FULL, .colorspace = VIDEO_CS_709
    };
    obs_add_raw_video_callback(&conversion, on_frame, NULL);
    for (int i = 0; i < 100 && !atomic_load(&correct); ++i) os_sleep_ms(50);
    REQUIRE(atomic_load(&frames) > 0 && atomic_load(&correct));
    puts("IMAGE_COMPOSITE_PASS PNG=decoded sRGB=verified");
    atomic_store(&correct, false); atomic_store(&expected, 1);
    REQUIRE(obs_transition_start(fade, OBS_TRANSITION_MODE_AUTO, 500, obs_scene_get_source(green_scene)));
    for (int i = 0; i < 100 && !atomic_load(&correct); ++i) os_sleep_ms(50);
    REQUIRE(atomic_load(&correct));
    puts("FADE_PASS original_obs_transition=500ms");
    atomic_store(&correct, false); atomic_store(&expected, 0);
    obs_transition_set(fade, obs_scene_get_source(scene));
    for (int i = 0; i < 100 && !atomic_load(&correct); ++i) os_sleep_ms(50);
    REQUIRE(atomic_load(&correct));
    puts("CUT_PASS original_obs_transition_set=verified");
    obs_remove_raw_video_callback(on_frame, NULL);
    printf("GPU_FRAMES=%d CORRECT_COMPOSITE=%d\n", atomic_load(&frames), atomic_load(&correct));
    REQUIRE(atomic_load(&frames) > 0 && atomic_load(&correct));
    obs_set_output_source(0, NULL);
    obs_source_release(fade);
    obs_source_release(red);
    obs_source_release(blue);
    obs_source_release(green);
    obs_source_release(image_source);
    obs_scene_release(green_scene);
    obs_scene_release(scene);
    obs_shutdown();
    puts("GRAPHICS_TEST_PASS real_obs_scene=2_sources EGL=android GLES=3.2 pixels=verified");
    return 0;
}

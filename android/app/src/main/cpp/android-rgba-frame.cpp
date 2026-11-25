// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-rgba-frame.h"
#include <obs.h>
#include <util/bmem.h>
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <vector>
#include <nlohmann/json.hpp>

namespace {
std::atomic<uint64_t> submitted{0}, gpu_imports{0}, upload_fallbacks{0}, busy_drops{0};
constexpr const char *effect_source = R"(
#include "color.effect"
uniform float4x4 ViewProj;
uniform texture2d image;
uniform bool premultiplied;
uniform bool linear_output;
sampler_state sample_image { Filter = Linear; AddressU = Clamp; AddressV = Clamp; };
struct Vertex { float4 pos : POSITION; float2 uv : TEXCOORD0; };
Vertex VS(Vertex v) { v.pos = mul(float4(v.pos.xyz, 1.0), ViewProj); return v; }
float4 PS(Vertex v) : TARGET {
    float4 rgba = image.Sample(sample_image, v.uv);
    if (premultiplied) rgba.rgb = rgba.a > 0.0 ? saturate(rgba.rgb / rgba.a) : float3(0.0, 0.0, 0.0);
    if (linear_output) rgba.rgb = srgb_nonlinear_to_linear(rgba.rgb);
    return rgba;
}
technique Draw { pass { vertex_shader = VS(v); pixel_shader = PS(v); } }
)";
}

struct AndroidRgbaFrame::State {
    struct Slot {
        AHardwareBuffer *buffer{};
        AHardwareBuffer_Desc desc{};
        std::vector<uint8_t> fallback;
        gs_texture_t *texture{};
        EGLImageKHR image{EGL_NO_IMAGE_KHR};
        EGLDisplay display{EGL_NO_DISPLAY};
        GLsync fence{};
        uint64_t revision{}, imported_revision{}, storage_revision{}, imported_storage{};
        uint32_t width{}, height{};
        bool ready{}, in_flight{}, premultiplied{true};
    };
    std::mutex mutex;
    std::array<Slot, 3> slots;
    int current{-1};
    uint64_t next_revision{};
    std::atomic<uint32_t> width{0}, height{0};
    gs_effect_t *effect{};
    bool effect_attempted{};
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC get_buffer{};
    PFNEGLCREATEIMAGEKHRPROC create_image{};
    PFNEGLDESTROYIMAGEKHRPROC destroy_image{};
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC bind_image{};

    void release_graphics(Slot &slot) {
        if (slot.fence) glDeleteSync(slot.fence);
        slot.fence = nullptr;
        if (slot.texture) gs_texture_destroy(slot.texture);
        slot.texture = nullptr;
        if (slot.image != EGL_NO_IMAGE_KHR && destroy_image) destroy_image(slot.display, slot.image);
        slot.image = EGL_NO_IMAGE_KHR;
    }

    bool import(Slot &slot) {
        if (slot.texture && slot.fallback.empty() && slot.image != EGL_NO_IMAGE_KHR && slot.imported_storage == slot.storage_revision) {
            slot.imported_revision = slot.revision;
            return true;
        }
        if (slot.texture && slot.imported_revision == slot.revision) return true;
        release_graphics(slot);
        slot.texture = gs_texture_create(slot.width, slot.height, GS_RGBA, 1, nullptr, GS_DYNAMIC);
        if (!slot.texture) return false;
        if (!slot.fallback.empty()) {
            gs_texture_set_image(slot.texture, slot.fallback.data(), slot.width * 4, false);
            ++upload_fallbacks;
        } else {
            if (!get_buffer) {
                get_buffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
                create_image = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
                destroy_image = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
                bind_image = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
            }
            if (get_buffer && create_image && destroy_image && bind_image) {
            slot.display = eglGetCurrentDisplay();
            const EGLint attrs[]{EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
            slot.image = create_image(slot.display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, get_buffer(slot.buffer), attrs);
            if (slot.image != EGL_NO_IMAGE_KHR) {
            GLint old = 0;
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &old);
            auto *texture_id = static_cast<const GLuint *>(gs_texture_get_obj(slot.texture));
            glBindTexture(GL_TEXTURE_2D, *texture_id);
            bind_image(GL_TEXTURE_2D, slot.image);
            glBindTexture(GL_TEXTURE_2D, GLuint(old));
            ++gpu_imports;
            }
            }
            if (slot.image == EGL_NO_IMAGE_KHR) {
                void *pixels = nullptr;
                if (AHardwareBuffer_lock(slot.buffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &pixels) != 0) return false;
                gs_texture_set_image(slot.texture, static_cast<const uint8_t *>(pixels), slot.desc.stride * 4, false);
                AHardwareBuffer_unlock(slot.buffer, nullptr);
                ++upload_fallbacks;
            }
        }
        slot.imported_storage = slot.storage_revision;
        slot.imported_revision = slot.revision;
        return true;
    }
};

AndroidRgbaFrame::AndroidRgbaFrame() : state(std::make_unique<State>()) {}
AndroidRgbaFrame::~AndroidRgbaFrame()
{
    obs_enter_graphics();
    for (auto &slot : state->slots) {
        state->release_graphics(slot);
        if (slot.buffer) AHardwareBuffer_release(slot.buffer);
    }
    if (state->effect) gs_effect_destroy(state->effect);
    obs_leave_graphics();
}

bool AndroidRgbaFrame::submit(const uint8_t *pixels, uint32_t w, uint32_t h, uint32_t stride, bool premultiplied, bool flip)
{
    if (!pixels || !w || !h || w > 3840 || h > 2160 || stride < w * 4) return false;
    auto &s = *state;
    std::lock_guard<std::mutex> lock(s.mutex);
    int index = -1;
    for (int i = 0; i < 3; ++i) {
        if (i != s.current && !s.slots[i].in_flight) {
            index = i;
            if (s.slots[i].ready) break; // Replace pending work instead of growing a queue.
        }
    }
    if (index < 0) { ++busy_drops; return false; }
    auto &slot = s.slots[index];
    if (!slot.buffer || slot.width != w || slot.height != h) {
        if (slot.buffer) AHardwareBuffer_release(slot.buffer);
        slot.buffer = nullptr;
        slot.desc = {};
        slot.desc.width = w; slot.desc.height = h; slot.desc.layers = 1;
        slot.desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        slot.desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        ++slot.storage_revision;
        if (AHardwareBuffer_allocate(&slot.desc, &slot.buffer) == 0)
            AHardwareBuffer_describe(slot.buffer, &slot.desc);
    }
    slot.width = w; slot.height = h; slot.premultiplied = premultiplied;
    void *destination = nullptr;
    if (slot.buffer && AHardwareBuffer_lock(slot.buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &destination) == 0) {
        for (uint32_t y = 0; y < h; ++y)
            std::memcpy(static_cast<uint8_t *>(destination) + size_t(y) * slot.desc.stride * 4, pixels + size_t(flip ? h - 1 - y : y) * stride, size_t(w) * 4);
        AHardwareBuffer_unlock(slot.buffer, nullptr);
        slot.fallback.clear();
    } else {
        slot.fallback.resize(size_t(w) * h * 4);
        for (uint32_t y = 0; y < h; ++y)
            std::memcpy(slot.fallback.data() + size_t(y) * w * 4, pixels + size_t(flip ? h - 1 - y : y) * stride, size_t(w) * 4);
    }
    slot.revision = ++s.next_revision;
    slot.ready = true;
    s.width.store(w); s.height.store(h);
    ++submitted;
    return true;
}

bool AndroidRgbaFrame::submitBitmap(JNIEnv *env, jobject bitmap)
{
    AndroidBitmapInfo info{};
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    const bool premultiplied = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
    bool accepted = submit(static_cast<const uint8_t *>(pixels), info.width, info.height, info.stride, premultiplied);
    AndroidBitmap_unlockPixels(env, bitmap);
    return accepted;
}

void AndroidRgbaFrame::render()
{
    auto &s = *state;
    std::lock_guard<std::mutex> lock(s.mutex);
    for (auto &slot : s.slots) {
        if (!slot.in_flight || !slot.fence) continue;
        const GLenum wait = glClientWaitSync(slot.fence, 0, 0);
        if (wait == GL_ALREADY_SIGNALED || wait == GL_CONDITION_SATISFIED) {
            glDeleteSync(slot.fence); slot.fence = nullptr; slot.in_flight = false;
        }
    }
    int newest = -1;
    for (int i = 0; i < 3; ++i)
        if (s.slots[i].ready && (newest < 0 || s.slots[i].revision > s.slots[newest].revision)) newest = i;
    if (newest >= 0 && s.import(s.slots[newest])) {
        s.current = newest;
        for (auto &slot : s.slots) slot.ready = false;
    }
    if (s.current < 0) return;
    if (!s.effect_attempted) {
        s.effect_attempted = true;
        char *path = obs_find_data_file("default.effect");
        char *error = nullptr;
        s.effect = gs_effect_create(effect_source, path ? path : "android-rgba.effect", &error);
        if (!s.effect) blog(LOG_ERROR, "[android-rgba] Shader creation failed: %s", error ? error : "unknown");
        bfree(error); bfree(path);
    }
    if (!s.effect) return;
    auto &slot = s.slots[s.current];
    const bool linear = gs_get_linear_srgb();
    const bool previous = gs_framebuffer_srgb_enabled();
    gs_enable_framebuffer_srgb(linear);
    gs_effect_set_texture(gs_effect_get_param_by_name(s.effect, "image"), slot.texture);
    gs_effect_set_bool(gs_effect_get_param_by_name(s.effect, "premultiplied"), slot.premultiplied);
    gs_effect_set_bool(gs_effect_get_param_by_name(s.effect, "linear_output"), linear);
    while (gs_effect_loop(s.effect, "Draw")) gs_draw_sprite(slot.texture, 0, slot.width, slot.height);
    gs_enable_framebuffer_srgb(previous);
    if (slot.fence) glDeleteSync(slot.fence);
    slot.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    slot.in_flight = true;
    glFlush();
}

uint32_t AndroidRgbaFrame::width() const { return state->width.load(); }
uint32_t AndroidRgbaFrame::height() const { return state->height.load(); }

extern "C" __attribute__((visibility("default"))) bool obs_android_rgba_stats(char *out, size_t capacity)
{
    auto json = nlohmann::json{{"submitted", submitted.load()}, {"gpu_imports", gpu_imports.load()},
        {"upload_fallbacks", upload_fallbacks.load()}, {"busy_drops", busy_drops.load()}}.dump();
    if (!out || json.size() >= capacity) return false;
    std::memcpy(out, json.c_str(), json.size() + 1);
    return true;
}

// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <jni.h>
#include <cstdint>
#include <memory>

// Bounded RGBA handoff. CPU writes only free buffers; a GPU fence protects
// each submitted buffer from reuse. Premultiplied alpha is divided in shader.
class AndroidRgbaFrame {
    struct State;
    std::unique_ptr<State> state;
public:
    AndroidRgbaFrame();
    ~AndroidRgbaFrame();
    bool submit(const uint8_t *pixels, uint32_t width, uint32_t height, uint32_t stride, bool premultiplied, bool flip = false);
    bool submitBitmap(JNIEnv *env, jobject bitmap);
    void render();
    uint32_t width() const;
    uint32_t height() const;
};

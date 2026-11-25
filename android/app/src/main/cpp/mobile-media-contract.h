// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include <cstdint>

// Platform-neutral descriptions passed from Android/iOS capture adapters to
// the shared OBS media path. Keep OS handles and framework headers out of this
// contract; each adapter owns and imports its native buffers.
namespace obs_mobile {

enum class VideoPixelFormat : uint8_t {
	Unknown,
	RGBA8888,
	YUV420P,
	OpaqueNative,
};

enum class ColorRange : uint8_t {
	Unknown,
	Full,
	Limited,
};

enum class ColorMatrix : uint8_t {
	Unknown,
	BT601,
	BT709,
	BT2020,
};

enum class AudioSampleFormat : uint8_t {
	Signed16,
	Float32,
};

struct VideoFrameMetadata {
	uint32_t width{};
	uint32_t height{};
	uint64_t presentation_time_ns{};
	VideoPixelFormat pixel_format{VideoPixelFormat::Unknown};
	ColorRange range{ColorRange::Unknown};
	ColorMatrix matrix{ColorMatrix::Unknown};
	uint16_t rotation_degrees{};
	bool mirrored{};
};

struct AudioFrameMetadata {
	uint32_t sample_rate{};
	uint32_t channels{};
	uint32_t frames_per_channel{};
	uint64_t presentation_time_ns{};
	AudioSampleFormat sample_format{AudioSampleFormat::Signed16};
};

struct VideoEncoderCapability {
	uint32_t max_width{};
	uint32_t max_height{};
	uint32_t max_fps_num{};
	uint32_t max_fps_den{1};
	uint32_t max_instances{};
	bool hardware{};
	bool accepts_native_surface{};
};

} // namespace obs_mobile

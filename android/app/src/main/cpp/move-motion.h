// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

// Kept independent of OBS/graphics so timing and overshoot can be tested on the host.
namespace move_motion {
inline float unit(float t) { return t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t); }

inline float easing(int mode, float time)
{
	const float t = unit(time);
	const float u = 1.0f - t;
	switch (mode) {
	case 0: return t < 0.5f ? 4.0f * t * t * t : 1.0f - 4.0f * u * u * u;
	case 1: return 1.0f - u * u * u;
	case 2: return 1.0f - 2.15f * u * u * u + 1.15f * u * u;
	case 3: return t < 0.5f ? 16.0f * t * t * t * t * t : 1.0f - 16.0f * u * u * u * u * u;
	default: return t;
	}
}

struct EntryMotion {
	float scale;
	float offset_x;
	float offset_y;
};

struct Basis { float xx, xy, yx, yy; };
struct ScaleShear { float x, y, shear; };

inline ScaleShear decompose(Basis basis, float c, float s)
{
	return {basis.xx * c + basis.xy * s,
		-basis.yx * s + basis.yy * c,
		basis.yx * c + basis.yy * s};
}

inline Basis compose(ScaleShear scale, float c, float s)
{
	return {scale.x * c, scale.x * s,
		scale.shear * c - scale.y * s, scale.shear * s + scale.y * c};
}

inline EntryMotion unmatched(int style, bool incoming, float progress, float width, float height)
{
	const float remaining = incoming ? 1.0f - progress : progress;
	EntryMotion out{1.0f, 0.0f, 0.0f};
	if (style == 1)
		out.scale = 1.0f - 0.18f * remaining;
	else if (style == 2)
		out.offset_y = height * 0.14f * remaining;
	else if (style == 3)
		out.offset_x = width * 0.18f * remaining * (incoming ? 1.0f : -1.0f);
	return out;
}
} // namespace move_motion

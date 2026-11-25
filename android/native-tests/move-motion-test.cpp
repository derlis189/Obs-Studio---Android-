// SPDX-License-Identifier: GPL-2.0-or-later
#include "../app/src/main/cpp/move-motion.h"

static bool near(float a, float b) { return (a - b < 0.0001f) && (b - a < 0.0001f); }
#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)

// No platform dependencies: this same test runs on Windows and builds for Android.
int main()
{
	using namespace move_motion;
	for (int mode = 0; mode <= 4; ++mode) {
		CHECK(near(easing(mode, -1.0f), 0.0f));
		CHECK(near(easing(mode, 2.0f), 1.0f));
		CHECK(near(easing(mode, 0.0f), 0.0f));
		CHECK(near(easing(mode, 1.0f), 1.0f));
		for (int style = 0; style <= 3; ++style) {
			auto in = unmatched(style, true, easing(mode, 1.0f), 1000.0f, 500.0f);
			auto out = unmatched(style, false, easing(mode, 0.0f), 1000.0f, 500.0f);
			CHECK(near(in.scale, 1.0f) && near(in.offset_x, 0.0f) && near(in.offset_y, 0.0f));
			CHECK(near(out.scale, 1.0f) && near(out.offset_x, 0.0f) && near(out.offset_y, 0.0f));
		}
	}
	// Regression: selecting Linear or Ease Out must change unmatched motion.
	auto linear = unmatched(3, true, easing(4, 0.25f), 1000.0f, 500.0f);
	auto cubic = unmatched(3, true, easing(0, 0.25f), 1000.0f, 500.0f);
	auto easeout = unmatched(3, true, easing(1, 0.25f), 1000.0f, 500.0f);
	CHECK(near(linear.offset_x, 135.0f));
	CHECK(cubic.offset_x > linear.offset_x && easeout.offset_x < linear.offset_x);
	CHECK(near(unmatched(1, true, easing(4, 0.25f), 1000, 500).scale, 0.865f));
	CHECK(near(unmatched(2, false, easing(4, 0.25f), 1000, 500).offset_y, 17.5f));
	// Back Out retains geometric overshoot while alpha remains valid.
	CHECK(easing(2, 0.8f) > 1.0f);
	CHECK(unmatched(1, true, easing(2, 0.8f), 1000, 500).scale > 1.0f);
	CHECK(near(unit(easing(2, 0.8f)), 1.0f));
	CHECK(near(easing(0, 0.25f), 0.0625f));
	CHECK(near(easing(3, 0.25f), 0.015625f));
	// Exact affine round trips: rotation, shear, non-uniform scale and mirrors.
	for (int mirror = 0; mirror < 2; ++mirror) {
		const float sign = mirror ? 1.0f : -1.0f;
		const Basis original{1.2f, 1.6f, -1.9f * sign, 3.3f * sign};
		const auto scale = decompose(original, 0.6f, 0.8f);
		const auto restored = compose(scale, 0.6f, 0.8f);
		CHECK(near(restored.xx, original.xx) && near(restored.xy, original.xy));
		CHECK(near(restored.yx, original.yx) && near(restored.yy, original.yy));
		CHECK(scale.shear != 0.0f);
		// Source center -> render translation must preserve the cropped origin.
		const float cx = 20 + original.xx * 150 + original.yx * 70;
		const float cy = 50 + original.xy * 150 + original.yy * 70;
		CHECK(near(cx - restored.xx * 150 - restored.yx * 70, 20.0f));
		CHECK(near(cy - restored.xy * 150 - restored.yy * 70, 50.0f));
	}
	return 0;
}

// SPDX-License-Identifier: GPL-2.0-or-later
#include "../../out/src/obs-studio/libobs/util/android-preview-pacing.h"
#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)

int main()
{
    for (int input = 30; input <= 60; input += 30) {
        const int limits[] = {5, 10, 12, 15};
        for (int index = 0; index < 4; ++index) {
            const int limit = limits[index];
            unsigned long long next = 0;
            int rendered = 0;
            for (int frame = 0; frame < input * 10; ++frame) {
                // Simulate the engine clock with small alternating scheduling jitter.
                auto now = 1000000000ULL + (unsigned long long)frame * 1000000000ULL / input
                    + (frame % 2 ? 200000ULL : 0ULL);
                if (obs_android_preview_due(now, next, false)) {
                    ++rendered;
                    next = obs_android_preview_next(now, next, 1000000000ULL / limit);
                }
            }
            CHECK(rendered >= limit * 10 - 1 && rendered <= limit * 10 + 1);
        }
    }
    CHECK(!obs_android_preview_due(100, 200, false));
    CHECK(obs_android_preview_due(100, 200, true)); // Resize must bypass the cap.
    CHECK(obs_android_preview_next(10000, 100, 500) == 10500); // No catch-up burst.
    return 0;
}

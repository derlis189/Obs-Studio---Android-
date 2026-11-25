// SPDX-License-Identifier: GPL-2.0-or-later
#include "../app/src/main/cpp/android-session-gate.h"
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>

int main()
{
    std::atomic<bool> entered{false}, release{false}, closed{false};
    std::atomic<unsigned> accepted{0}, rejected{0};
    std::thread in_flight([&] {
        AndroidCallbackScope callback;
        if (!callback) return;
        entered = true;
        while (!release.load()) std::this_thread::yield();
    });
    while (!entered.load()) std::this_thread::yield();
    std::vector<std::thread> producers;
    for (int i = 0; i < 6; ++i) producers.emplace_back([&] {
        for (unsigned frame = 0; frame < 10000; ++frame) {
            AndroidCallbackScope callback;
            if (callback) ++accepted;
            else ++rejected;
        }
    });
    std::thread shutdown([&] { android_session_close(); closed = true; });
    while (android_session_gate().open.load()) std::this_thread::yield();
    bool incorrectly_accepted = false;
    {
        AndroidCallbackScope late;
        incorrectly_accepted = bool(late);
    }
    // Closing must wait for an actual delivery, but reject new producers immediately.
    std::this_thread::sleep_for(std::chrono::milliseconds(25));
    const bool closed_early = closed.load();
    release = true;
    in_flight.join(); shutdown.join();
    for (auto &producer : producers) producer.join();
    if (incorrectly_accepted || closed_early || !closed || accepted + rejected != 60000) {
        std::puts("SESSION_GATE_FAIL");
        return 1;
    }
    for (unsigned i = 0; i < 10000; ++i) {
        AndroidCallbackScope late;
        if (late) return 2;
    }
    std::printf("SESSION_GATE_PASS accepted=%u rejected=%u; drained in-flight delivery and rejected late callbacks\n",
                accepted.load(), rejected.load());
}

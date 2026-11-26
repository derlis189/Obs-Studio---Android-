// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <atomic>
#include <shared_mutex>
#include <mutex>

// Process-lifetime gate: Android producers can outlive Qt's event loop. Closing
// waits for in-flight deliveries before OBS starts destroying its mutexes.
struct AndroidSessionGate {
    std::shared_mutex mutex;
    std::atomic<bool> open{true};
};
inline AndroidSessionGate &android_session_gate()
{
    static auto *gate = new AndroidSessionGate;
    return *gate;
}
struct AndroidCallbackScope {
    std::shared_lock<std::shared_mutex> lock;
    bool accepted{};
    AndroidCallbackScope() : lock(android_session_gate().mutex, std::defer_lock) {
        if (!android_session_gate().open.load(std::memory_order_acquire)) return;
        lock.lock();
        accepted = android_session_gate().open.load(std::memory_order_acquire);
    }
    explicit operator bool() const { return accepted; }
};
inline bool android_session_close()
{
    // Reject new entrants before waiting for callbacks already holding a lock.
    const bool was_open = android_session_gate().open.exchange(false, std::memory_order_acq_rel);
    std::unique_lock<std::shared_mutex> drain(android_session_gate().mutex);
    return was_open;
}
inline void android_session_reopen()
{
    std::unique_lock<std::shared_mutex> lock(android_session_gate().mutex);
    android_session_gate().open.store(true, std::memory_order_release);
}

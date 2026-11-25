// SPDX-License-Identifier: GPL-2.0-or-later
#include <jni.h>
#include <cstdint>
#include <type_traits>
static_assert(sizeof(std::uintptr_t) == 8);
static_assert(std::is_pointer_v<JavaVM *>);

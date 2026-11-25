# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.28...3.30)
project(android_toolchain_preflight LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "This preflight requires the Android NDK and arm64-v8a")
endif()

# Toolchain verification only. This target neither builds nor substitutes libobs.
add_library(android-toolchain-probe SHARED toolchain-probe.c toolchain-probe.cpp)
target_compile_features(android-toolchain-probe PRIVATE c_std_17 cxx_std_17)
target_compile_options(android-toolchain-probe PRIVATE -Wall -Wextra -Werror)
target_link_options(android-toolchain-probe PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)
target_link_libraries(android-toolchain-probe PRIVATE android log EGL GLESv3 mediandk camera2ndk aaudio dl m)

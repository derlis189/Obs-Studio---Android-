#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-or-later
set -euo pipefail
root=$(cygpath -u "$(cygpath -d "$(cd "$(dirname "$0")/.." && pwd)")")
ndk=${ANDROID_NDK_ROOT:-/c/Users/derli/AppData/Local/Android/Sdk/ndk/29.0.14206865}
ndk=$(cygpath -u "$ndk")
llvm="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
src="$root/upstream/deps/ffmpeg"
build="$root/out/build/ffmpeg-arm64"
prefix="$root/out/deps/arm64-v8a"
mkdir -p "$build" "$prefix"
cd "$build"
export PATH="$llvm:$PATH"
# Git Bash plus NDK make avoids introducing a Linux/glibc toolchain.
cc="$(cygpath -m "$llvm/clang.exe") --target=aarch64-linux-android29"
if [[ ! -f ffbuild/config.mak || ! -f ffbuild/android-config-v2 ]]; then
  bash "$src/configure" \
    --prefix="$(cygpath -m "$prefix")" \
    --target-os=android --arch=aarch64 --enable-cross-compile \
    --cc="$cc" --cxx="$(cygpath -m "$llvm/clang++.exe") --target=aarch64-linux-android29" \
    --host-cc="$cc" \
    --ar="$(cygpath -m "$llvm/llvm-ar.exe")" \
    --nm="$(cygpath -m "$llvm/llvm-nm.exe")" \
    --ranlib="$(cygpath -m "$llvm/llvm-ranlib.exe")" \
    --strip="$(cygpath -m "$llvm/llvm-strip.exe")" \
    --enable-static --disable-shared --enable-pic --disable-symver \
    --disable-programs --disable-doc --disable-debug --disable-autodetect \
    --disable-avdevice --disable-avfilter --disable-gpl --disable-nonfree --enable-zlib \
    --extra-cflags='-O2 -fPIC' --extra-ldflags='-Wl,-z,max-page-size=16384'
  printf 'PNG and zlib enabled\n' > ffbuild/android-config-v2
fi
# The NDK ships native Windows make; translate configure's MSYS source path in
# generated makefiles only. Keep the pinned FFmpeg source tree untouched.
native_src=$(cygpath -m "$src")
sed -i "s|$src|$native_src|g" Makefile ffbuild/config.mak
# Native Windows shells cap an echo command before a large archive object list.
# GNU make's file function writes the response file without a shell or truncation.
cp "$src/Makefile" ffbuild/android-Makefile
cp "$src/ffbuild/library.mak" ffbuild/android-library.mak
sed -i 's|include $(SRC_PATH)/ffbuild/library.mak|include ffbuild/android-library.mak|' ffbuild/android-Makefile
sed -i 's|$(Q)echo $\^ > $@.objs|$(file >$@.objs,$^)|' ffbuild/android-library.mak
printf 'include ffbuild/android-Makefile\n' > Makefile
make_bin="$ndk/prebuilt/windows-x86_64/bin/make.exe"
make_shell=$(cygpath -m "$(cygpath -d /usr/bin/sh.exe)")
"$make_bin" -j6 "SHELL=$make_shell"
"$make_bin" install V=1 "SHELL=$make_shell"

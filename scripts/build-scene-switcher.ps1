# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$source = Join-Path $ProjectRoot 'out/scene-switcher-src'
$revision = '6b8e666d5826d3c0784d2bd16c1d309b296f1b7b'
if (-not (Test-Path "$source/.git")) {
    & git clone --branch 1.37.2 --depth 1 https://github.com/WarmUpTill/SceneSwitcher.git $source
    if ($LASTEXITCODE) { throw 'Scene Switcher clone failed' }
}
if ((& git -C $source rev-parse HEAD) -ne $revision) { throw 'Unexpected Scene Switcher revision' }
$patch = Join-Path $ProjectRoot 'patches/scene-switcher/0001-android-core-base.patch'
& git -C $source apply --reverse --check $patch 2>$null
if ($LASTEXITCODE) {
    & git -C $source apply --check $patch
    if ($LASTEXITCODE) { throw 'Scene Switcher changes conflict with the Android patch' }
    & git -C $source apply $patch
    if ($LASTEXITCODE) { throw 'Scene Switcher patch failed' }
}
& git -C $source submodule update --init --depth 1 deps/asio deps/json
if ($LASTEXITCODE) { throw 'Scene Switcher dependency checkout failed' }
$cmake = Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64/bin/cmake.exe'
$build = Join-Path $ProjectRoot 'out/android-scene-switcher'
$prefix = Join-Path $ProjectRoot 'out/deps/arm64-v8a'
$qt = Join-Path $ProjectRoot 'tools/qt6/6.9.3/android_arm64_v8a'
$hostQt = Join-Path $ProjectRoot 'tools/qt6/6.9.3/mingw_64'
$argsConfigure = @('-S', "$ProjectRoot/android/scene-switcher", '-B', $build, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$SdkRoot/cmake/3.22.1/bin/ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$SdkRoot/ndk/29.0.14206865/build/cmake/android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_shared',
    "-DCMAKE_PREFIX_PATH=$prefix;$qt", "-DCMAKE_FIND_ROOT_PATH=$prefix;$qt", "-DQT_HOST_PATH=$hostQt",
    '-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY', '-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY',
    '-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY', '-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER',
    '-DCMAKE_BUILD_TYPE=RelWithDebInfo', '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON')
$result = Invoke-Recorded $cmake $argsConfigure "$build/configure.log"
if ($result.exitCode) { throw "Scene Switcher configure failed: $($result.log)" }
$result = Invoke-Recorded $cmake @('--build', $build, '--parallel', '4') "$build/build.log"
if ($result.exitCode) { throw "Scene Switcher build failed: $($result.log)" }

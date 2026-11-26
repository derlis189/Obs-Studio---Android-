# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot, [switch]$CoreOnly, [switch]$Streaming, [switch]$Editor, [switch]$Frontend)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$cmake = Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64/bin/cmake.exe'
$source = Join-Path $ProjectRoot 'out/src/obs-studio'
$build = Join-Path $ProjectRoot 'out/libobs-arm64'
$prefix = Join-Path $ProjectRoot 'out/deps/arm64-v8a'
$qtAndroid = Join-Path $ProjectRoot 'tools/qt6/6.9.3/android_arm64_v8a'
$qtHost = Join-Path $ProjectRoot 'tools/qt6/6.9.3/mingw_64'
$Editor = $Editor -or $Frontend
$Streaming = $Streaming -or $Editor
$phase = if ($Frontend) { 7 } elseif ($Editor) { 6 } elseif ($Streaming) { 5 } elseif ($CoreOnly) { 2 } else { 3 }
$logs = Join-Path $ProjectRoot "out/phase$phase/logs"
$graphics = if ($CoreOnly) { 'OFF' } else { 'ON' }
$outputs = if ($Streaming) { 'ON' } else { 'OFF' }
$editorModules = if ($Editor) { 'ON' } else { 'OFF' }
$frontendOpt = if ($Frontend) { 'ON' } else { 'OFF' }
$prefixPaths = if ($Frontend) { "$prefix;$qtAndroid" } else { $prefix }
if ($Editor) {
    & (Join-Path $PSScriptRoot 'prepare-advanced-masks.ps1')
    & (Join-Path $PSScriptRoot 'prepare-stroke-glow-shadow.ps1')
}
$common = @('-S', $source, '-B', $build, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$SdkRoot/cmake/3.22.1/bin/ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$SdkRoot/ndk/29.0.14206865/build/cmake/android.toolchain.cmake",
    '-DCMAKE_SYSTEM_NAME=Android', '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_shared',
    "-DCMAKE_PREFIX_PATH=$prefixPaths", "-DCMAKE_FIND_ROOT_PATH=$prefixPaths", "-DCMAKE_INSTALL_PREFIX=$ProjectRoot/out/install/arm64-v8a",
    "-DQT_HOST_PATH=$qtHost",
    '-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY', '-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY',
    '-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY', '-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER',
    '-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF', '-DCMAKE_EXPORT_NO_PACKAGE_REGISTRY=ON',
    '-DCMAKE_BUILD_TYPE=RelWithDebInfo', '-DCMAKE_COMPILE_WARNING_AS_ERROR=OFF', "-DOBS_ANDROID_GRAPHICS=$graphics", "-DOBS_ANDROID_OUTPUTS=$outputs", "-DOBS_ANDROID_EDITOR=$editorModules",
    "-DOBS_ANDROID_HARNESS_DIR=$ProjectRoot/android/native-tests", "-DENABLE_FRONTEND=$frontendOpt", '-DENABLE_SCRIPTING=OFF', '-DENABLE_CORE_MODULES=OFF',
    '-DOBS_VERSION_OVERRIDE=0.0.1-android-dev', '-DOBS_BUILD_NUMBER=1', '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON')
if ($Editor) {
    $common += "-DOBS_ADVANCED_MASKS_SOURCE_DIR=$ProjectRoot/out/advanced-masks-src"
    $common += "-DOBS_STROKE_GLOW_SHADOW_SOURCE_DIR=$ProjectRoot/out/stroke-glow-shadow-src"
}
$result = Invoke-Recorded $cmake $common (Join-Path $logs 'libobs-configure.log')
if ($result.exitCode) { throw "OBS configure failed: $($result.log)" }
$targets = @('libobs', 'obs-android-core-test')
if (-not $CoreOnly) { $targets += @('libobs-opengl', 'image-source', 'obs-transitions', 'obs-android-graphics-test') }
if ($Streaming) { $targets += @('obs-outputs', 'obs-android-media-probe') }
if ($Editor) { $targets += @('obs-filters', 'obs-ffmpeg', 'vlc-video') }
if ($Editor) { $targets += 'obs-advanced-masks' }
if ($Editor) { $targets += 'obs-stroke-glow-shadow' }
if ($Frontend) { $targets += @('obs-frontend-api', 'obs-studio') }
$result = Invoke-Recorded $cmake (@('--build', $build, '--target') + $targets + @('--parallel', '6')) (Join-Path $logs 'libobs-build.log')
if ($result.exitCode) { throw "OBS compile/link failed: $($result.log)" }

# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Serial, [string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$logs = Join-Path $ProjectRoot 'out/phase3/logs'
$remote = '/data/local/tmp/obs-android-port'
$build = Join-Path $ProjectRoot 'out/libobs-arm64'
$source = Join-Path $ProjectRoot 'out/src/obs-studio'
$pushes = @(
    @("$build/libobs/libobs.so", "$remote/libobs.so"),
    @("$build/libobs-opengl/libobs-opengl.so", "$remote/libobs-opengl.so"),
    @("$build/plugins/image-source/libimage-source.so", "$remote/libimage-source.so"),
    @("$build/plugins/obs-transitions/libobs-transitions.so", "$remote/libobs-transitions.so"),
    @("$build/android-native-tests/obs-android-graphics-test", "$remote/obs-android-graphics-test"),
    @("$source/libobs/data/.", "$remote/data"),
    @("$ProjectRoot/android/test-assets/android-test.png", "$remote/data/android-test.png"),
    @("$source/plugins/image-source/data/.", "$remote/files/obs-modules/core/image-source"),
    @("$source/plugins/obs-transitions/data/.", "$remote/files/obs-modules/core/obs-transitions")
)
$mkdirs = @("$remote/files/obs-modules/core/image-source", "$remote/files/obs-modules/core/obs-transitions", "$remote/data")
if (Test-Path -LiteralPath "$build/plugins/obs-outputs/libobs-outputs.so") {
    $pushes += ,@("$build/plugins/obs-outputs/libobs-outputs.so", "$remote/libobs-outputs.so")
}
if (Test-Path -LiteralPath "$build/plugins/obs-filters/libobs-filters.so") {
    $mkdirs += "$remote/files/obs-modules/core/obs-filters"
    $pushes += @(
        @("$build/plugins/obs-filters/libobs-filters.so", "$remote/libobs-filters.so"),
        @("$source/plugins/obs-filters/data/.", "$remote/files/obs-modules/core/obs-filters")
    )
}
if (Test-Path -LiteralPath "$build/plugins/obs-ffmpeg/libobs-ffmpeg.so") {
    $mkdirs += "$remote/files/obs-modules/core/obs-ffmpeg"
    $pushes += @(
        @("$build/plugins/obs-ffmpeg/libobs-ffmpeg.so", "$remote/libobs-ffmpeg.so"),
        @("$source/plugins/obs-ffmpeg/data/.", "$remote/files/obs-modules/core/obs-ffmpeg")
    )
}
$libvlc = Join-Path $ProjectRoot 'out/deps/libvlc/extracted/jni/arm64-v8a/libvlc.so'
if (Test-Path -LiteralPath "$build/plugins/vlc-video/libvlc-video.so") {
    if (-not (Test-Path -LiteralPath $libvlc)) { throw 'Run build-vlc-dependencies.ps1 first.' }
    $mkdirs += "$remote/files/obs-modules/core/vlc-video"
    $pushes += @(
        @("$build/plugins/vlc-video/libvlc-video.so", "$remote/libvlc-video.so"),
        @($libvlc, "$remote/libvlc.so"),
        @("$source/plugins/vlc-video/data/.", "$remote/files/obs-modules/core/vlc-video")
    )
}
$result = Invoke-Recorded $adb (@('-s', $Serial, 'shell', 'mkdir', '-p') + $mkdirs) (Join-Path $logs 'graphics-mkdir.log')
if ($result.exitCode) { throw 'Cannot prepare graphics test directory' }
$index = 0
foreach ($push in $pushes) {
    $result = Invoke-Recorded $adb @('-s', $Serial, 'push', $push[0], $push[1]) (Join-Path $logs "graphics-push-$index.log")
    if ($result.exitCode) { throw "Graphics push failed: $($result.log)" }
    $index++
}
$result = Invoke-Recorded $adb @('-s', $Serial, 'shell', 'chmod', '755', "$remote/obs-android-graphics-test") (Join-Path $logs 'graphics-chmod.log')
if ($result.exitCode) { throw 'chmod failed' }
$result = Invoke-Recorded $adb @('-s', $Serial, 'shell', "LD_LIBRARY_PATH=$remote timeout 30 $remote/obs-android-graphics-test $remote/files $remote/data $remote >$remote/graphics-runtime.log 2>&1") (Join-Path $logs 'device-graphics-command.log')
$nativeResult = $result.exitCode
$runtimeLog = Join-Path $logs 'device-graphics-test.log'
if (Test-Path -LiteralPath $runtimeLog) { Move-Item -LiteralPath $runtimeLog -Destination "$runtimeLog.previous-$(Get-Date -Format yyyyMMdd-HHmmss-fff)" }
$pull = Invoke-Recorded $adb @('-s', $Serial, 'pull', "$remote/graphics-runtime.log", $runtimeLog) (Join-Path $logs 'device-graphics-pull.log')
if ($nativeResult -or $pull.exitCode -or (Get-Content -LiteralPath $runtimeLog -Raw) -notmatch 'GRAPHICS_TEST_PASS') { throw "Graphics runtime test failed: $runtimeLog" }
Write-Host 'Native GPU composition/readback passed; window/UI validation remains separate'

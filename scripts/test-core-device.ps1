# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$Serial, [string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
if (-not $Serial) {
    $devices = @(& $adb devices | Select-String -Pattern '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Pass -Serial for the intended connected ARM64 device' }
    $Serial = $devices[0]
}
$logs = Join-Path $ProjectRoot 'out/phase2/logs'
$readelf = Join-Path $SdkRoot 'ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
$library = Join-Path $ProjectRoot 'out/libobs-arm64/libobs/libobs.so'
$binary = Join-Path $ProjectRoot 'out/libobs-arm64/android-native-tests/obs-android-core-test'
$elf = Invoke-Recorded $readelf @('-h', '-l', '-d', '--dyn-syms', $library) (Join-Path $logs 'libobs-elf.log')
if ($elf.exitCode -or $elf.output -notmatch 'Machine:\s+AArch64' -or $elf.output -match 'libc\.so\.6|libpthread\.so|\.dll') { throw 'Invalid Android ELF' }
foreach ($symbol in @('obs_startup', 'obs_shutdown', 'obs_scene_create', 'obs_reset_audio', 'obs_source_create', 'obs_output_create', 'obs_android_set_paths')) {
    if ($elf.output -notmatch "\b$symbol\b") { throw "Missing libobs export: $symbol" }
}
$badAlignment = @($elf.output -split "`n" | Where-Object { $_ -match '^\s*LOAD\s' -and $_ -notmatch '0x4000\s*$' })
if ($badAlignment.Count) { throw 'libobs LOAD alignment is not 16 KB' }
$remote = '/data/local/tmp/obs-android-port'
$steps = @(
    @{ args = @('-s', $Serial, 'shell', 'mkdir', '-p', "$remote/files", "$remote/data"); name = 'device-mkdir' },
    @{ args = @('-s', $Serial, 'push', $library, "$remote/libobs.so"); name = 'device-push-libobs' },
    @{ args = @('-s', $Serial, 'push', $binary, "$remote/obs-android-core-test"); name = 'device-push-core-test' },
    @{ args = @('-s', $Serial, 'shell', 'chmod', '755', "$remote/obs-android-core-test"); name = 'device-chmod' },
    @{ args = @('-s', $Serial, 'shell', "LD_LIBRARY_PATH=$remote $remote/obs-android-core-test $remote/files $remote/data $remote"); name = 'device-core-test' }
)
foreach ($step in $steps) {
    $result = Invoke-Recorded $adb $step.args (Join-Path $logs "$($step.name).log")
    if ($result.exitCode) { throw "Device test step failed: $($result.log)" }
}
if ($result.output -notmatch 'CORE_TEST_PASS cycles=3') { throw "Core test did not report success: $($result.log)" }
@{
    phase = 2; compile = 'PASS'; nativeDeviceTest = 'PASS'; serial = $Serial
    sourceCommit = $SourceLock.commit; libobsSha256 = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash
    libobsBytes = (Get-Item -LiteralPath $library).Length
    scope = 'Real libobs lifecycle, nested scenes, UUID lookup, volume/mute, JSON persistence, 48 kHz stereo audio engine; no graphics, capture or stream'
    observedAt = (Get-Date -Format o)
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ProjectRoot 'out/phase2/report.json') -Encoding utf8
Write-Host 'PHASE 2 PASS: ARM64 libobs compiled and native core tests passed on device'

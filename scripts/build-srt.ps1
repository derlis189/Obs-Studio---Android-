# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$lock = Get-Content -LiteralPath (Join-Path $ProjectRoot 'dependencies-lock.json') -Raw | ConvertFrom-Json
$source = Join-Path $ProjectRoot 'upstream/deps/srt'
$srt = $lock.sources | Where-Object name -eq srt
if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $srt.commit -or
    (Invoke-GitChecked @('-C', $source, 'status', '--porcelain'))) {
    throw 'Run build-dependencies.ps1 to obtain the clean pinned SRT sources.'
}
$prefix = Join-Path $ProjectRoot 'out/deps/arm64-v8a'
if (-not (Test-Path -LiteralPath (Join-Path $prefix 'lib/libmbedtls.a'))) {
    throw 'Run build-stream-dependencies.ps1 to build Mbed TLS.'
}
$cmake = Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64/bin/cmake.exe'
$build = Join-Path $ProjectRoot 'out/build/srt-arm64'
$logs = Join-Path $ProjectRoot 'out/deps/logs'
$config = @('-S', $source, '-B', $build, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$SdkRoot/cmake/3.22.1/bin/ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$SdkRoot/ndk/$($lock.ndk)/build/cmake/android.toolchain.cmake",
    "-DANDROID_ABI=$($lock.abi)", "-DANDROID_PLATFORM=android-$($lock.androidApi)",
    '-DANDROID_STL=c++_shared', '-DCMAKE_BUILD_TYPE=Release',
    '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', "-DCMAKE_INSTALL_PREFIX=$prefix",
    '-DENABLE_APPS=OFF', '-DENABLE_SHARED=OFF', '-DENABLE_STATIC=ON',
    '-DENABLE_ENCRYPTION=ON', '-DUSE_ENCLIB=mbedtls', "-DMBEDTLS_INCLUDE_DIR=$prefix/include",
    "-DMBEDTLS_LIB=$prefix/lib/libmbedtls.a", "-DMBEDCRYPTO_LIB=$prefix/lib/libmbedcrypto.a",
    "-DMBEDX509_LIB=$prefix/lib/libmbedx509.a")
foreach ($step in @(
    @{ args = $config; log = 'srt-configure.log' },
    @{ args = @('--build', $build, '--parallel', '6'); log = 'srt-build.log' },
    @{ args = @('--install', $build); log = 'srt-install.log' }
)) {
    $result = Invoke-Recorded $cmake $step.args (Join-Path $logs $step.log)
    if ($result.exitCode) { throw "SRT step failed: $($result.log)" }
}

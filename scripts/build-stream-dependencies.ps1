# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$lock = Get-Content (Join-Path $ProjectRoot 'dependencies-lock.json') -Raw | ConvertFrom-Json
$dep = $lock.sources | Where-Object name -eq 'mbedtls'
$source = Join-Path $ProjectRoot 'upstream/deps/mbedtls'
if (-not (Test-Path -LiteralPath $source)) {
    Invoke-GitChecked @('clone', '--depth', '1', '--branch', $dep.tag, '--recurse-submodules', '--shallow-submodules', $dep.url, $source) | Out-Host
}
if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $dep.commit) { throw 'MbedTLS commit mismatch' }
if (Invoke-GitChecked @('-C', $source, 'status', '--porcelain', '--ignore-submodules=all')) { throw 'Modified MbedTLS checkout' }
foreach ($module in $dep.submodules) {
    $path = Join-Path $source $module.path
    if (-not (Test-Path -LiteralPath "$path/.git")) { throw 'Missing MbedTLS framework' }
    if ((Invoke-GitChecked @('-C', $path, 'rev-parse', 'HEAD')) -ne $module.commit) { throw 'MbedTLS framework commit mismatch' }
    if (Invoke-GitChecked @('-C', $path, 'status', '--porcelain')) { throw 'Modified MbedTLS framework' }
}
$cmake = Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64/bin/cmake.exe'
$build = Join-Path $ProjectRoot 'out/build/mbedtls-arm64'
$prefix = Join-Path $ProjectRoot 'out/deps/arm64-v8a'
$configure = @('-S', $source, '-B', $build, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$SdkRoot/cmake/3.22.1/bin/ninja.exe", "-DCMAKE_TOOLCHAIN_FILE=$SdkRoot/ndk/$($lock.ndk)/build/cmake/android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29', '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
    "-DCMAKE_INSTALL_PREFIX=$prefix", '-DENABLE_PROGRAMS=OFF', '-DENABLE_TESTING=OFF', '-DGEN_FILES=OFF', '-DMBEDTLS_FATAL_WARNINGS=OFF')
foreach ($step in @(@{args=$configure; log='configure'}, @{args=@('--build',$build,'--parallel','6');log='build'}, @{args=@('--install',$build);log='install'})) {
    $result = Invoke-Recorded $cmake $step.args (Join-Path $ProjectRoot "out/phase5/logs/mbedtls-$($step.log).log")
    if ($result.exitCode) { throw "MbedTLS failed: $($result.log)" }
}

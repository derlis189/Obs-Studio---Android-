# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$lock = Get-Content (Join-Path $ProjectRoot 'dependencies-lock.json') -Raw | ConvertFrom-Json
foreach ($name in @('curl', 'nlohmann_json')) {
    $dep = $lock.sources | Where-Object name -eq $name
    $source = Join-Path $ProjectRoot "upstream/deps/$name"
    if (-not (Test-Path -LiteralPath $source)) {
        Invoke-GitChecked @('clone', '--depth', '1', '--branch', $dep.tag, $dep.url, $source) | Out-Host
    }
    if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $dep.commit) { throw "$name commit mismatch" }
    if (Invoke-GitChecked @('-C', $source, 'status', '--porcelain')) { throw "Modified $name checkout" }
}
$cmake = Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64/bin/cmake.exe'
$prefix = Join-Path $ProjectRoot 'out/deps/arm64-v8a'
$logs = Join-Path $ProjectRoot 'out/phase7/logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$common = @(
    '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$SdkRoot/cmake/3.22.1/bin/ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$SdkRoot/ndk/$($lock.ndk)/build/cmake/android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-29',
    '-DCMAKE_BUILD_TYPE=Release',
    '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
    "-DCMAKE_PREFIX_PATH=$prefix",
    "-DCMAKE_FIND_ROOT_PATH=$prefix",
    "-DCMAKE_INSTALL_PREFIX=$prefix"
)

# 1. nlohmann_json (header-only)
$jsonBuild = Join-Path $ProjectRoot 'out/build/nlohmann-json-arm64'
$jsonConfig = @('-S', (Join-Path $ProjectRoot 'upstream/deps/nlohmann_json'), '-B', $jsonBuild) + $common + @('-DJSON_BuildTests=OFF')
foreach ($step in @(@{args=$jsonConfig;log='json-configure'}, @{args=@('--build',$jsonBuild,'--parallel','6');log='json-build'}, @{args=@('--install',$jsonBuild);log='json-install'})) {
    $result = Invoke-Recorded $cmake $step.args (Join-Path $logs "$($step.log).log")
    if ($result.exitCode) { throw "nlohmann_json failed: $($result.log)" }
}

# 2. libcurl (static library with MbedTLS)
$curlBuild = Join-Path $ProjectRoot 'out/build/curl-arm64'
$curlConfig = @('-S', (Join-Path $ProjectRoot 'upstream/deps/curl'), '-B', $curlBuild) + $common + @(
    '-DBUILD_CURL_EXE=OFF',
    '-DBUILD_SHARED_LIBS=OFF',
    '-DBUILD_TESTING=OFF',
    '-DBUILD_LIBCURL_DOCS=OFF',
    '-DBUILD_MISC_DOCS=OFF',
    '-DENABLE_CURL_MANUAL=OFF',
    '-DCURL_USE_MBEDTLS=ON',
    '-DCURL_USE_OPENSSL=OFF',
    '-DCURL_USE_LIBPSL=OFF',
    '-DCURL_USE_LIBSSH2=OFF',
    '-DUSE_LIBIDN2=OFF',
    '-DUSE_NGHTTP2=OFF',
    '-DCURL_BROTLI=OFF',
    '-DCURL_ZSTD=OFF',
    '-DHTTP_ONLY=ON'
)
foreach ($step in @(@{args=$curlConfig;log='curl-configure'}, @{args=@('--build',$curlBuild,'--parallel','6');log='curl-build'}, @{args=@('--install',$curlBuild);log='curl-install'})) {
    $result = Invoke-Recorded $cmake $step.args (Join-Path $logs "$($step.log).log")
    if ($result.exitCode) { throw "curl failed: $($result.log)" }
}
Write-Host "Frontend dependencies staged: $prefix"

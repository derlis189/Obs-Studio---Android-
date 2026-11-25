# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot, [switch]$SkipFFmpeg)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$lock = Get-Content (Join-Path $ProjectRoot 'dependencies-lock.json') -Raw | ConvertFrom-Json
foreach ($dependency in $lock.sources) {
    $source = Join-Path $ProjectRoot "upstream/deps/$($dependency.name)"
    if (-not (Test-Path -LiteralPath $source)) {
        Invoke-GitChecked @('clone', '--depth', '1', '--branch', $dependency.tag, $dependency.url, $source) | Out-Host
    }
    $head = Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')
    if ($head -ne $dependency.commit) { throw "Dependency commit mismatch: $($dependency.name)" }
    foreach ($submodule in ($dependency | Select-Object -ExpandProperty submodules -ErrorAction SilentlyContinue)) {
        Invoke-GitChecked @('-C', $source, 'submodule', 'update', '--init', '--recursive', '--', $submodule.path) | Out-Host
        $submodulePath = Join-Path $source $submodule.path
        $submoduleHead = Invoke-GitChecked @('-C', $submodulePath, 'rev-parse', 'HEAD')
        if ($submoduleHead -ne $submodule.commit) { throw "Submodule commit mismatch: $submodulePath" }
    }
    if (Invoke-GitChecked @('-C', $source, 'status', '--porcelain')) { throw "Modified dependency: $source" }
}
$cmake = Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64/bin/cmake.exe'
$prefix = Join-Path $ProjectRoot 'out/deps/arm64-v8a'
$build = Join-Path $ProjectRoot 'out/build/jansson-arm64'
$logs = Join-Path $ProjectRoot 'out/phase2/logs'
$common = @('-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$SdkRoot/cmake/3.22.1/bin/ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$SdkRoot/ndk/$($lock.ndk)/build/cmake/android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29', '-DCMAKE_BUILD_TYPE=Release',
    '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', "-DCMAKE_INSTALL_PREFIX=$prefix")
$argsConfigure = @('-S', (Join-Path $ProjectRoot 'upstream/deps/jansson'), '-B', $build) + $common + @(
    '-DJANSSON_BUILD_SHARED_LIBS=OFF', '-DJANSSON_WITHOUT_TESTS=ON', '-DJANSSON_EXAMPLES=OFF', '-DJANSSON_BUILD_DOCS=OFF')
foreach ($step in @(
    @{ args = $argsConfigure; log = 'jansson-configure.log' },
    @{ args = @('--build', $build, '--parallel', '6'); log = 'jansson-build.log' },
    @{ args = @('--install', $build); log = 'jansson-install.log' }
)) {
    $result = Invoke-Recorded $cmake $step.args (Join-Path $logs $step.log)
    if ($result.exitCode) { throw "Dependency step failed: $($result.log)" }
}
New-Item -ItemType Directory -Force -Path (Join-Path $prefix 'include') | Out-Null
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'upstream/deps/simde/simde') -Destination (Join-Path $prefix 'include') -Recurse -Force
Copy-Item -Path (Join-Path $ProjectRoot 'upstream/deps/uthash/src/*.h') -Destination (Join-Path $prefix 'include') -Force
if (-not $SkipFFmpeg) {
    $env:ANDROID_NDK_ROOT = "$SdkRoot/ndk/$($lock.ndk)"
    $result = Invoke-Recorded (Resolve-GitBash) @((Join-Path $PSScriptRoot 'build-ffmpeg.sh')) (Join-Path $logs 'ffmpeg-build.log')
    if ($result.exitCode) { throw "FFmpeg failed: $($result.log)" }
}
Write-Host "ARM64 dependencies staged: $prefix"

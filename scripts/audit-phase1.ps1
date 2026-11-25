# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$NdkVersion = '29.0.14206865',
    [string]$CmakePath,
    [string]$NinjaPath
)
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-ObsSource
if (-not $SdkRoot) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    if (-not (Test-Path -LiteralPath $SdkRoot)) {
        # Desktop sandboxes may redirect LOCALAPPDATA away from the installed SDK.
        $SdkRoot = Join-Path $env:USERPROFILE 'AppData/Local/Android/Sdk'
    }
}
if (-not $CmakePath) { $CmakePath = Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64/bin/cmake.exe' }
if (-not $NinjaPath) { $NinjaPath = Join-Path $SdkRoot 'cmake/3.22.1/bin/ninja.exe' }
$ndkRoot = Join-Path $SdkRoot "ndk/$NdkVersion"
$llvmBin = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$clang = Join-Path $llvmBin 'clang.exe'
$readelf = Join-Path $llvmBin 'llvm-readelf.exe'
$toolchain = Join-Path $ndkRoot 'build/cmake/android.toolchain.cmake'
foreach ($required in @($CmakePath, $NinjaPath, $clang, $readelf, $toolchain)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing tool: $required. See docs/DEPENDENCIAS.md" }
}
$outputRoot = Join-Path $ProjectRoot 'out/phase1'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$logs = Join-Path $outputRoot 'logs'
$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed; detail = $Detail })
    Write-Host "${Name}: $Passed"
}

$cmakeVersion = Invoke-Recorded $CmakePath @('--version') (Join-Path $logs 'cmake-version.log')
Add-Check 'CMake >= 3.28' ($cmakeVersion.exitCode -eq 0 -and $cmakeVersion.output -match 'cmake version (\d+\.\d+\.\d+)' -and [version]$Matches[1] -ge [version]'3.28.0') $cmakeVersion.log
$ninjaVersion = Invoke-Recorded $NinjaPath @('--version') (Join-Path $logs 'ninja-version.log')
Add-Check 'Ninja' ($ninjaVersion.exitCode -eq 0) $ninjaVersion.log
$clangVersion = Invoke-Recorded $clang @('--version') (Join-Path $logs 'clang-version.log')
Add-Check 'NDK Clang' ($clangVersion.exitCode -eq 0) $clangVersion.log

# Trace real source locations. A missing pattern is drift and fails the audit.
$evidenceSpecs = @(
    @('minimum-cmake', 'CMakeLists.txt', 'cmake_minimum_required\(VERSION 3\.28'),
    @('host-vs-target', 'cmake/common/osconfig.cmake', 'CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows"'),
    @('windows-architecture', 'CMakeLists.txt', 'include\("\$\{CMAKE_CURRENT_SOURCE_DIR\}/cmake/windows/architecture.cmake"\)'),
    @('ffmpeg8', 'libobs/CMakeLists.txt', 'find_package\(FFmpeg 8\.0'),
    @('android-loader-missing', 'libobs/CMakeLists.txt', 'PLATFORM_ID:Linux,FreeBSD,OpenBSD>:obs-core-modules-loader-nix.c'),
    @('x11', 'libobs/cmake/os-linux.cmake', 'find_package\(X11 REQUIRED\)'),
    @('uuid', 'libobs/util/platform-nix.c', '#include <uuid/uuid.h>'),
    @('desktop-gl-version', 'libobs-opengl/gl-subsystem.c', 'if \(!GLAD_GL_VERSION_3_3\)'),
    @('desktop-glsl', 'libobs-opengl/gl-shaderparser.c', '#version 330'),
    @('desktop-readback', 'libobs-opengl/gl-stagesurf.c', 'glGetTexImage\(GL_TEXTURE_2D'),
    @('qt', 'frontend/cmake/ui-qt.cmake', 'find_package\(Qt6 REQUIRED'),
    @('tls', 'plugins/obs-outputs/CMakeLists.txt', 'find_package\(MbedTLS 3'),
    @('mux-subprocess', 'plugins/obs-ffmpeg/obs-ffmpeg-mux.c', 'os_process_pipe_create2'),
    @('simde-arm', 'libobs/util/sse-intrin.h', '#include <simde/x86/sse2.h>')
)
$evidence = foreach ($spec in $evidenceSpecs) {
    $hits = @(Select-String -LiteralPath (Join-Path $ObsSource $spec[1]) -Pattern $spec[2])
    Add-Check "Source: $($spec[0])" ($hits.Count -gt 0) $spec[1]
    foreach ($hit in $hits) {
        [pscustomobject]@{ id = $spec[0]; file = $spec[1]; line = $hit.LineNumber; text = $hit.Line.Trim() }
    }
}
$evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRoot 'source-evidence.json') -Encoding utf8

$matrix = @(Import-Csv -LiteralPath (Join-Path $ProjectRoot 'docs/plugins.csv'))
$plugins = @(Get-ChildItem -LiteralPath (Join-Path $ObsSource 'plugins') -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'CMakeLists.txt')
} | Select-Object -ExpandProperty Name)
$difference = @(Compare-Object ($plugins | Sort-Object) ($matrix.plugin | Sort-Object))
Add-Check 'All official plugin directories classified' ($difference.Count -eq 0 -and ($matrix.plugin | Sort-Object -Unique).Count -eq $matrix.Count) "$($plugins.Count) directories"

# These configure-only runs deliberately expose upstream blockers; no libobs compilation.
$androidArgs = @(
    '-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$NinjaPath", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_shared'
)
$obsArgs = @(
    '-S', $ObsSource, '-B', (Join-Path $outputRoot 'upstream-configure')
) + $androidArgs + @(
    '-DENABLE_FRONTEND=OFF', '-DENABLE_SCRIPTING=OFF', '-DENABLE_CORE_MODULES=OFF',
    '-DOBS_BUILD_NUMBER=1', '-DOBS_VERSION_OVERRIDE=0.0.1-android-audit'
)
# Override avoids git-describe on a shallow non-release commit. It is an audit label,
# never a claimed upstream release. Build number override prevents writing into upstream.
$upstream = Invoke-Recorded $CmakePath $obsArgs (Join-Path $logs 'upstream-android-configure.log')
Add-Check 'Upstream Android blocker reproduced' ($upstream.exitCode -ne 0 -and $upstream.output -match 'OBS requires Windows SDK|Unsupported generator platform for Windows builds') $upstream.log

$oldCmake = Join-Path $SdkRoot 'cmake/3.22.1/bin/cmake.exe'
if (Test-Path -LiteralPath $oldCmake) {
    $oldArgs = @('-S', $ObsSource, '-B', (Join-Path $outputRoot 'upstream-old-cmake')) + $androidArgs
    $old = Invoke-Recorded $oldCmake $oldArgs (Join-Path $logs 'upstream-old-cmake.log')
    Add-Check 'Installed CMake 3.22 rejected by upstream' ($old.exitCode -ne 0 -and $old.output -match 'CMake 3.28 or higher is required') $old.log
}

$probeSource = Join-Path $ProjectRoot 'android/preflight'
$probeBuild = Join-Path $outputRoot 'ndk-toolchain'
$configure = Invoke-Recorded $CmakePath (@('-S', $probeSource, '-B', $probeBuild) + $androidArgs) (Join-Path $logs 'ndk-configure.log')
Add-Check 'NDK toolchain configuration' ($configure.exitCode -eq 0) $configure.log
if ($configure.exitCode -eq 0) {
    $build = Invoke-Recorded $CmakePath @('--build', $probeBuild, '--verbose') (Join-Path $logs 'ndk-build.log')
    Add-Check 'ARM64 toolchain-only shared library' ($build.exitCode -eq 0) $build.log
    if ($build.exitCode -eq 0) {
        $library = Join-Path $probeBuild 'libandroid-toolchain-probe.so'
        $elf = Invoke-Recorded $readelf @('-h', '-l', '-d', $library) (Join-Path $logs 'ndk-elf.log')
        Add-Check 'AArch64 ELF and 16 KB LOAD alignment' ($elf.exitCode -eq 0 -and $elf.output -match 'Machine:\s+AArch64' -and $elf.output -match 'Class:\s+ELF64' -and $elf.output -match 'LOAD' -and -not ($elf.output -split "`n" | Where-Object { $_ -match '^\s*LOAD\s' -and $_ -notmatch '0x4000\s*$' })) $elf.log
    }
}

$uuid = Invoke-Recorded $clang @('--target=aarch64-linux-android29', '-fsyntax-only', (Join-Path $probeSource 'unsupported-uuid.c')) (Join-Path $logs 'ndk-uuid-incompatibility.log')
Add-Check 'NDK lacks desktop libuuid header' ($uuid.exitCode -ne 0 -and $uuid.output -match "'uuid/uuid.h' file not found") $uuid.log
$gl = Invoke-Recorded $clang @('--target=aarch64-linux-android29', '-Werror=implicit-function-declaration', '-fsyntax-only', (Join-Path $probeSource 'unsupported-desktop-gl.c')) (Join-Path $logs 'ndk-desktop-gl-incompatibility.log')
Add-Check 'GLES lacks desktop glGetTexImage' ($gl.exitCode -ne 0 -and $gl.output -match 'undeclared function.*glGetTexImage') $gl.log
Assert-ObsSource
Add-Check 'Official source and submodules preserved' $true $SourceLock.commit

$failed = @($checks | Where-Object { -not $_.passed })
$report = [ordered]@{
    phase = 1
    sourceCommit = $SourceLock.commit
    observedAt = (Get-Date -Format o)
    result = $(if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' })
    scope = 'Source audit and NDK compile/link preflight only. No libobs build, APK or device execution.'
    libobsBuild = 'NOT_STARTED_PHASE_2'
    androidSdk = $SdkRoot
    ndkVersion = $NdkVersion
    checks = @($checks.ToArray())
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputRoot 'report.json') -Encoding utf8
if ($failed.Count) { throw "Phase 1 audit failed: $($failed.name -join ', '). Inspect $outputRoot" }
Write-Host "Phase 1 PASS: $($checks.Count) checks. libobs / APK NOT BUILT. Report: $outputRoot/report.json"

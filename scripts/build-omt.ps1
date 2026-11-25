# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot, [string]$DotnetPath)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
if (-not $DotnetPath) {
    $portable = Join-Path $ProjectRoot 'out/omt-tools/dotnet/dotnet.exe'
    $DotnetPath = if (Test-Path -LiteralPath $portable) {
        $portable
    } else {
        (Get-Command dotnet.exe -CommandType Application | Select-Object -First 1).Source
    }
}
$lock = Get-Content -LiteralPath (Join-Path $ProjectRoot 'dependencies-lock.json') -Raw | ConvertFrom-Json
$ndkBin = Join-Path $SdkRoot "ndk/$($lock.ndk)/toolchains/llvm/prebuilt/windows-x86_64/bin"
$clang = Join-Path $ndkBin 'clang++.exe'
if (-not (Test-Path -LiteralPath $clang)) { throw "Android NDK compiler not found: $clang" }
$work = Join-Path $ProjectRoot 'out/omt-src'
$stage = Join-Path $ProjectRoot 'out/omt-arm64'
$logs = Join-Path $stage 'logs'
New-Item -ItemType Directory -Force -Path $work, $stage | Out-Null

foreach ($name in @('libvmx', 'libomtnet', 'omtplugin')) {
    $source = Join-Path $work $name
    $revision = $lock.omt.$name
    if (-not (Test-Path -LiteralPath $source)) {
        Invoke-GitChecked @('init', $source) | Out-Null
        Invoke-GitChecked @('-C', $source, 'remote', 'add', 'origin', "https://github.com/openmediatransport/$name.git")
    }
    if (-not (& git -C $source rev-parse --verify HEAD 2>$null)) {
        Invoke-GitChecked @('-C', $source, 'fetch', '--depth', '1', 'origin', $revision) | Out-Host
        Invoke-GitChecked @('-C', $source, 'checkout', '--detach', 'FETCH_HEAD') | Out-Host
    }
    if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $revision) {
        throw "Unexpected revision in $source"
    }
    $patch = Join-Path $ProjectRoot "patches/omt/$name-android.patch"
    if (Test-Path -LiteralPath $patch) {
        & git -C $source apply --reverse --check $patch 2>$null
        if ($LASTEXITCODE -ne 0) {
            if (Invoke-GitChecked @('-C', $source, 'status', '--porcelain', '--untracked-files=no')) {
                throw "Unexpected changes in $source"
            }
            Invoke-GitChecked @('-C', $source, 'apply', $patch)
        }
    }
}
$vmx = Join-Path $work 'libvmx/src'
$result = Invoke-Recorded $clang @("--target=aarch64-linux-android$($lock.androidApi)", '-O3',
    '-std=c++17', '-fdeclspec', '-fPIC', '-shared', '-Wl,-soname,libvmx.so',
    '-Wl,-z,max-page-size=16384', (Join-Path $vmx 'vmxcodec_arm.cpp'),
    (Join-Path $vmx 'vmxcodec.cpp'), '-o', (Join-Path $stage 'libvmx.so')) (Join-Path $logs 'vmx.log')
if ($result.exitCode) { throw "VMX build failed: $($result.log)" }

$originalPath = $env:PATH
try {
    $env:PATH = "$ndkBin;$originalPath"
    $result = Invoke-Recorded $DotnetPath @('build', (Join-Path $work 'libomtnet/libomtnet.csproj'),
        '-c', 'Release', '-p:TargetFrameworks=netstandard2.0', '--nologo') (Join-Path $logs 'libomtnet.log')
    if ($result.exitCode) { throw "libomtnet build failed: $($result.log)" }
    $result = Invoke-Recorded $DotnetPath @('publish', (Join-Path $work 'omtplugin/omtplugin.csproj'),
        '-c', 'Release', '-r', 'linux-bionic-arm64', '-p:DisableUnsupportedError=true',
        '-p:PublishAotUsingRuntimePack=true', '--nologo') (Join-Path $logs 'omtplugin.log')
    if ($result.exitCode) { throw "OMT Native AOT build failed: $($result.log)" }
} finally {
    $env:PATH = $originalPath
}
$plugin = Join-Path $work 'omtplugin/bin/Release/net8.0/linux-bionic-arm64/publish/omtplugin.so'
Copy-Item -LiteralPath $plugin -Destination (Join-Path $stage 'libomtplugin.so') -Force
$notices = Join-Path $stage 'notices'
New-Item -ItemType Directory -Force -Path $notices | Out-Null
foreach ($name in @('libvmx', 'libomtnet', 'omtplugin')) {
    Copy-Item -LiteralPath (Join-Path $work "$name/LICENSE.txt") -Destination (Join-Path $notices "$name.txt") -Force
}
Write-Host "Android ARM64 OMT staged in $stage"

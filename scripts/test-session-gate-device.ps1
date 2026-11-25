# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Serial, [string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$taskAdb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$taskCompiler = Join-Path $SdkRoot 'ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang++.cmd'
$taskResults = Join-Path $ProjectRoot 'out/performance-checks'
New-Item -ItemType Directory -Force -Path $taskResults | Out-Null
$taskBinary = Join-Path $taskResults 'session-gate-test'
& $taskCompiler -std=c++17 -O2 -static-libstdc++ (Join-Path $ProjectRoot 'android/native-tests/session-gate-test.cpp') -o $taskBinary '-Wl,-z,max-page-size=16384'
if ($LASTEXITCODE) { throw 'Session callback gate test did not compile' }
$taskRemote = '/data/local/tmp/obs-session-gate-test'
& $taskAdb -s $Serial push $taskBinary $taskRemote
if ($LASTEXITCODE) { throw 'Could not deploy session gate test' }
& $taskAdb -s $Serial shell chmod 755 $taskRemote
if ($LASTEXITCODE) { throw 'Could not mark session gate test executable' }
$taskResult = Invoke-Recorded $taskAdb @('-s', $Serial, 'shell', $taskRemote) (Join-Path $taskResults 'session-gate-test.log')
if ($taskResult.exitCode) { throw "Session callback gate regression: $($taskResult.log)" }
Write-Host $taskResult.output

# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$taskLlvm = Join-Path $SdkRoot 'ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin'
$taskResults = Join-Path $ProjectRoot 'out/move-stinger-checks'
New-Item -ItemType Directory -Force -Path $taskResults | Out-Null
$taskSource = Join-Path $ProjectRoot 'android/native-tests/move-motion-test.cpp'
$taskObject = Join-Path $taskResults 'move-motion-test.obj'
$taskExe = Join-Path $taskResults 'move-motion-test.exe'
& "$taskLlvm/clang++.exe" --target=x86_64-pc-windows-msvc -O2 -c $taskSource -o $taskObject
if ($LASTEXITCODE) { throw 'Move motion test compilation failed' }
& "$taskLlvm/ld.lld.exe" -flavor link /entry:main /subsystem:console /nodefaultlib "/out:$taskExe" $taskObject
if ($LASTEXITCODE) { throw 'Move motion test linking failed' }
& $taskExe
$taskTestCode = $LASTEXITCODE
"MOVE_MOTION_TEST_EXIT=$taskTestCode" | Set-Content -LiteralPath (Join-Path $taskResults 'move-motion-test.log')
if ($taskTestCode) { throw "Move regression test failed at line $taskTestCode" }
Write-Host 'MOVE_MOTION_PASS: easing, zoom/slide, opacity bounds, affine transforms and mirrors'

# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$taskJava = Resolve-JavaHome
$taskResults = Join-Path $ProjectRoot 'out/thermal-checks'
$taskClasses = Join-Path $taskResults 'classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskSources = @(
    'android/app/src/main/java/org/obsandroid/port/ThermalBudget.java',
    'android/app/src/main/java/org/obsandroid/port/BrowserFrameGate.java',
    'android/native-tests/ThermalBudgetTest.java'
) | ForEach-Object { Join-Path $ProjectRoot $_ }
& "$taskJava/bin/javac.exe" -d $taskClasses @taskSources
if ($LASTEXITCODE) { throw 'Thermal policy test compilation failed' }
& "$taskJava/bin/java.exe" -cp $taskClasses org.obsandroid.port.ThermalBudgetTest
if ($LASTEXITCODE) { throw 'Thermal policy/notification regression test failed' }
$taskLlvm = Join-Path $SdkRoot 'ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin'
$taskObject = Join-Path $taskResults 'preview-pacing-test.obj'
$taskExe = Join-Path $taskResults 'preview-pacing-test.exe'
$taskNativeSource = Join-Path $ProjectRoot 'android/native-tests/preview-pacing-test.cpp'
& "$taskLlvm/clang++.exe" --target=x86_64-pc-windows-msvc -O2 -c $taskNativeSource -o $taskObject
if ($LASTEXITCODE) { throw 'Preview pacing test compilation failed' }
& "$taskLlvm/ld.lld.exe" -flavor link /entry:main /subsystem:console /nodefaultlib "/out:$taskExe" $taskObject
if ($LASTEXITCODE) { throw 'Preview pacing test linking failed' }
& $taskExe
if ($LASTEXITCODE) { throw "Preview pacing test failed at line $LASTEXITCODE" }
Write-Host 'PREVIEW_PACING_PASS: 30/60 Hz engine, 5/10/12/15 Hz preview, jitter, resize and idle recovery'

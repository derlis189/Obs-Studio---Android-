# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([string]$SdkRoot, [string]$GradlePath, [switch]$WithOmt, [switch]$WithSceneSwitcher)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$env:JAVA_HOME = Resolve-JavaHome
if (-not $GradlePath) {
    $GradlePath = Join-Path $ProjectRoot 'android/gradlew.bat'
}
if (-not (Test-Path -LiteralPath $GradlePath)) { throw "Gradle launcher not found: $GradlePath" }
$cmakeDir = (Join-Path $ProjectRoot 'tools/cmake-3.30.8-windows-x86_64').Replace('\', '/')
$sdk = $SdkRoot.Replace('\', '/')
New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a') | Out-Null
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'out/libobs-arm64/libobs/libobs.so') -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a/libobs.so') -Force
$nativeFiles = @('libobs-opengl/libobs-opengl.so', 'plugins/image-source/libimage-source.so', 'plugins/obs-transitions/libobs-transitions.so')
foreach ($relative in $nativeFiles) {
    Copy-Item -LiteralPath (Join-Path $ProjectRoot "out/libobs-arm64/$relative") -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a') -Force
}
foreach ($module in @('obs-filters', 'obs-ffmpeg', 'vlc-video')) {
    $binary = Join-Path $ProjectRoot "out/libobs-arm64/plugins/$module/lib$module.so"
    if (Test-Path -LiteralPath $binary) {
        Copy-Item -LiteralPath $binary -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a') -Force
        New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot "android/app/src/main/assets/obs-modules/core/$module") | Out-Null
        Copy-Item -Path (Join-Path $ProjectRoot "out/src/obs-studio/plugins/$module/data/*") -Destination (Join-Path $ProjectRoot "android/app/src/main/assets/obs-modules/core/$module") -Recurse -Force
    }
}
$advancedMasksSource = Join-Path $ProjectRoot 'out/advanced-masks-src'
$advancedMasksBinary = Join-Path $ProjectRoot 'out/libobs-arm64/plugins/obs-advanced-masks/libobs-advanced-masks.so'
if (Test-Path -LiteralPath $advancedMasksBinary) {
    Copy-Item -LiteralPath $advancedMasksBinary -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a') -Force
    $advancedMasksData = Join-Path $ProjectRoot 'android/app/src/main/assets/obs-modules/core/obs-advanced-masks'
    New-Item -ItemType Directory -Force -Path $advancedMasksData | Out-Null
    Copy-Item -Path (Join-Path $advancedMasksSource 'data/*') -Destination $advancedMasksData -Recurse -Force
    $advancedMasksLicense = Join-Path $ProjectRoot 'android/app/src/main/assets/licenses/advanced-masks'
    New-Item -ItemType Directory -Force -Path $advancedMasksLicense | Out-Null
    Copy-Item -LiteralPath (Join-Path $advancedMasksSource 'LICENSE') -Destination (Join-Path $advancedMasksLicense 'LICENSE') -Force
}
$strokeGlowShadowSource = Join-Path $ProjectRoot 'out/stroke-glow-shadow-src'
$strokeGlowShadowBinary = Join-Path $ProjectRoot 'out/libobs-arm64/plugins/obs-stroke-glow-shadow/libobs-stroke-glow-shadow.so'
if (Test-Path -LiteralPath $strokeGlowShadowBinary) {
    Copy-Item -LiteralPath $strokeGlowShadowBinary -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a') -Force
    $strokeGlowShadowData = Join-Path $ProjectRoot 'android/app/src/main/assets/obs-modules/core/obs-stroke-glow-shadow'
    New-Item -ItemType Directory -Force -Path $strokeGlowShadowData | Out-Null
    Copy-Item -Path (Join-Path $strokeGlowShadowSource 'data/*') -Destination $strokeGlowShadowData -Recurse -Force
    $strokeGlowShadowLicense = Join-Path $ProjectRoot 'android/app/src/main/assets/licenses/stroke-glow-shadow'
    New-Item -ItemType Directory -Force -Path $strokeGlowShadowLicense | Out-Null
    Copy-Item -LiteralPath (Join-Path $strokeGlowShadowSource 'LICENSE') -Destination (Join-Path $strokeGlowShadowLicense 'LICENSE') -Force
}
foreach ($extraPlugin in @(
    @{ Name = 'obs-composite-blur'; Src = 'out/composite-blur-src'; Lic = 'composite-blur' },
    @{ Name = 'source-clone'; Src = 'out/source-clone-src'; Lic = 'source-clone' },
    @{ Name = 'downstream-keyer'; Src = 'out/downstream-keyer-src'; Lic = 'downstream-keyer' }
)) {
    $pSrc = Join-Path $ProjectRoot $extraPlugin.Src
    $pBin = Join-Path $ProjectRoot "out/libobs-arm64/plugins/$($extraPlugin.Name)/lib$($extraPlugin.Name).so"
    if (Test-Path -LiteralPath $pBin) {
        Copy-Item -LiteralPath $pBin -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a') -Force
        $pData = Join-Path $ProjectRoot "android/app/src/main/assets/obs-modules/core/$($extraPlugin.Name)"
        New-Item -ItemType Directory -Force -Path $pData | Out-Null
        if (Test-Path -LiteralPath (Join-Path $pSrc 'data')) {
            Copy-Item -Path (Join-Path $pSrc 'data/*') -Destination $pData -Recurse -Force
        }
        $pLicDir = Join-Path $ProjectRoot "android/app/src/main/assets/licenses/$($extraPlugin.Lic)"
        New-Item -ItemType Directory -Force -Path $pLicDir | Out-Null
        $pLicFile = Join-Path $pSrc 'LICENSE'
        if (Test-Path -LiteralPath $pLicFile) {
            Copy-Item -LiteralPath $pLicFile -Destination (Join-Path $pLicDir 'LICENSE') -Force
        }
    }
}
$libvlcBin = Join-Path $ProjectRoot 'out/deps/libvlc/extracted/jni/arm64-v8a/libvlc.so'
if (Test-Path -LiteralPath $libvlcBin) {
    Copy-Item -LiteralPath $libvlcBin -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a/libvlc.so') -Force
}
if (Test-Path -LiteralPath (Join-Path $ProjectRoot 'out/libobs-arm64/plugins/obs-outputs/libobs-outputs.so')) {
    Copy-Item -LiteralPath (Join-Path $ProjectRoot 'out/libobs-arm64/plugins/obs-outputs/libobs-outputs.so') -Destination (Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a') -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot 'android/app/src/main/assets/obs-modules/core/obs-outputs') | Out-Null
    Copy-Item -Path (Join-Path $ProjectRoot 'out/src/obs-studio/plugins/obs-outputs/data/*') -Destination (Join-Path $ProjectRoot 'android/app/src/main/assets/obs-modules/core/obs-outputs') -Recurse -Force
}
$assetRoot = Join-Path $ProjectRoot 'android/app/src/main/assets'
New-Item -ItemType Directory -Force -Path "$assetRoot/libobs", "$assetRoot/obs-modules/core/image-source", "$assetRoot/obs-modules/core/obs-transitions" | Out-Null
Copy-Item -Path (Join-Path $ProjectRoot 'out/src/obs-studio/libobs/data/*') -Destination "$assetRoot/libobs" -Recurse -Force
Copy-Item -Path (Join-Path $ProjectRoot 'out/src/obs-studio/plugins/image-source/data/*') -Destination "$assetRoot/obs-modules/core/image-source" -Recurse -Force
Copy-Item -Path (Join-Path $ProjectRoot 'out/src/obs-studio/plugins/obs-transitions/data/*') -Destination "$assetRoot/obs-modules/core/obs-transitions" -Recurse -Force
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'android/test-assets/android-test.png') -Destination "$assetRoot/libobs/android-test.png" -Force

$frontendLib = Join-Path $ProjectRoot 'out/libobs-arm64/frontend/libobs-studio_arm64-v8a.so'
if (Test-Path -LiteralPath $frontendLib) {
    $jniDest = Join-Path $ProjectRoot 'out/android/jniLibs/arm64-v8a'
    Copy-Item -LiteralPath $frontendLib -Destination $jniDest -Force
    Copy-Item -LiteralPath (Join-Path $ProjectRoot 'out/libobs-arm64/frontend/api/libobs-frontend-api.so') -Destination $jniDest -Force
    $qtRoot = Join-Path $ProjectRoot 'tools/qt6/6.9.3/android_arm64_v8a'
    foreach ($qtLib in @('Core', 'Gui', 'Network', 'OpenGL', 'OpenGLWidgets', 'Svg', 'Widgets', 'Xml')) {
        Copy-Item -LiteralPath (Join-Path $qtRoot "lib/libQt6${qtLib}_arm64-v8a.so") -Destination $jniDest -Force
    }
    foreach ($qtPlugin in @(
        'platforms/libplugins_platforms_qtforandroid_arm64-v8a.so',
        'iconengines/libplugins_iconengines_qsvgicon_arm64-v8a.so',
        'imageformats/libplugins_imageformats_qsvg_arm64-v8a.so',
        'imageformats/libplugins_imageformats_qjpeg_arm64-v8a.so',
        'imageformats/libplugins_imageformats_qgif_arm64-v8a.so',
        'imageformats/libplugins_imageformats_qico_arm64-v8a.so',
        'styles/libplugins_styles_qandroidstyle_arm64-v8a.so'
    )) {
        Copy-Item -LiteralPath (Join-Path $qtRoot "plugins/$qtPlugin") -Destination $jniDest -Force
    }
    New-Item -ItemType Directory -Force -Path "$assetRoot/obs-studio/authors" | Out-Null
    Copy-Item -Path (Join-Path $ProjectRoot 'out/src/obs-studio/frontend/data/*') -Destination "$assetRoot/obs-studio" -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $ProjectRoot 'out/src/obs-studio/AUTHORS') -Destination "$assetRoot/obs-studio/authors/AUTHORS" -Force
}
@("sdk.dir=$sdk", "cmake.dir=$cmakeDir") | Set-Content -LiteralPath (Join-Path $ProjectRoot 'android/local.properties') -Encoding ascii
$gradleArgs = @('-p', (Join-Path $ProjectRoot 'android'), ':app:assembleDebug', '--console=plain')
if ($WithSceneSwitcher) {
    $switcherStage = Join-Path $ProjectRoot 'out/android-scene-switcher'
    foreach ($name in @('advanced-scene-switcher', 'advanced-scene-switcher-lib', 'advanced-scene-switcher-base')) {
        if (-not (Test-Path -LiteralPath "$switcherStage/jniLibs/arm64-v8a/lib$name.so")) {
            throw 'Run build-scene-switcher.ps1 before packaging Scene Switcher.'
        }
    }
    $switcherSource = Join-Path $ProjectRoot 'out/scene-switcher-src'
    $switcherAssets = "$switcherStage/assets/obs-modules/core/advanced-scene-switcher"
    New-Item -ItemType Directory -Force -Path $switcherAssets, "$switcherStage/assets/licenses/advanced-scene-switcher" | Out-Null
    Copy-Item -Path "$switcherSource/data/*" -Destination $switcherAssets -Recurse -Force
    Copy-Item -LiteralPath "$switcherSource/LICENSE" -Destination "$switcherStage/assets/licenses/advanced-scene-switcher/LICENSE" -Force
    Copy-Item -LiteralPath "$switcherSource/deps/json/LICENSE.MIT" -Destination "$switcherStage/assets/licenses/advanced-scene-switcher/LICENSE-json" -Force
    Copy-Item -LiteralPath "$switcherSource/deps/asio/LICENSE_1_0.txt" -Destination "$switcherStage/assets/licenses/advanced-scene-switcher/LICENSE-asio" -Force
    Copy-Item -LiteralPath "$switcherSource/deps/websocketpp/COPYING" -Destination "$switcherStage/assets/licenses/advanced-scene-switcher/LICENSE-websocketpp" -Force
    Copy-Item -LiteralPath (Join-Path $ProjectRoot 'docs/SCENE-SWITCHER-SOURCE.txt') -Destination "$switcherStage/assets/licenses/advanced-scene-switcher/SOURCE.txt" -Force
    $gradleArgs += '-PwithSceneSwitcher=true'
}
if ($WithOmt) {
    $omtStage = Join-Path $ProjectRoot 'out/omt-arm64'
    $omtDest = Join-Path $ProjectRoot 'out/android-omt/jniLibs/arm64-v8a'
    New-Item -ItemType Directory -Force -Path $omtDest | Out-Null
    foreach ($file in @('libomtplugin.so', 'libvmx.so')) {
        $binary = Join-Path $omtStage $file
        if (-not (Test-Path -LiteralPath $binary)) { throw 'Run build-omt.ps1 before packaging OMT.' }
        Copy-Item -LiteralPath $binary -Destination $omtDest -Force
    }
    $gradleArgs += '-PwithOmt=true'
}
$result = Invoke-Recorded $GradlePath $gradleArgs (Join-Path $ProjectRoot 'out/android/logs/assemble-debug.log')
if ($result.exitCode) { throw "APK build failed: $($result.log)" }
$apk = Join-Path $ProjectRoot 'android/app/build/outputs/apk/debug/app-debug.apk'
Write-Host "Built APK: $apk"
Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Select-Object Hash,Path

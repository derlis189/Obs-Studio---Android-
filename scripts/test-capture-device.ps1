# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Serial, [string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$logs = Join-Path $ProjectRoot 'out/phase4/logs'
# Start screen + playback capture using fresh Android consent before running.
$result = Invoke-Recorded $adb @('-s', $Serial, 'shell', 'run-as', 'org.obsandroid.port', 'rm', '-f', 'files/capture-probe.json') (Join-Path $logs 'remove-old-probe.log')
if ($result.exitCode) { throw 'Install and open the debug APK first' }
$result = Invoke-Recorded $adb @('-s', $Serial, 'shell', 'am', 'start', '-n', 'org.obsandroid.port/.CaptureProbeActivity') (Join-Path $logs 'launch-probe.log')
if ($result.exitCode) { throw 'Cannot launch debug capture stimulus' }
$deadline = (Get-Date).AddSeconds(45)
$json = $null
do {
    Start-Sleep -Seconds 2
    $raw = @(& $adb -s $Serial shell run-as org.obsandroid.port cat files/capture-probe.json 2>$null)
    if ($LASTEXITCODE -eq 0) { $json = $raw -join "`n"; break }
} while ((Get-Date) -lt $deadline)
if (-not $json) { throw 'Capture probe timed out; check fresh projection consent and logcat' }
$probe = $json | ConvertFrom-Json
$json | Set-Content -LiteralPath (Join-Path $ProjectRoot 'out/phase4/capture-probe.json') -Encoding utf8
$result = Invoke-Recorded $adb @('-s', $Serial, 'logcat', '-d', '-s', 'ObsScreen:I', 'ObsCamera:I', 'ObsAudio:I', 'ObsProbe:I', 'ObsCaptureService:E', 'LibObsAndroid:E', 'AndroidRuntime:E') (Join-Path $logs 'capture-device.log')
$report = [ordered]@{
    device = $Serial; checkedUtc = [DateTime]::UtcNow.ToString('o')
    apkSha256 = (Get-FileHash -LiteralPath (Join-Path $ProjectRoot 'android/app/build/outputs/apk/debug/app-debug.apk')).Hash
    pass = $probe.pass; probe = $probe
    limits = @('Single Samsung device; controlled same-app USAGE_MEDIA stimulus', 'No measured A/V lip-sync, DRM/protected content, prolonged thermal test or other OEMs', 'Camera and microphone require separate runtime/lifecycle checks')
}
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $ProjectRoot 'out/phase4/report.json') -Encoding utf8
if (-not $probe.pass) { throw 'Real capture verification failed; see out/phase4/capture-probe.json' }
Write-Host 'CAPTURE_PASS screen pixels -> OBS GPU readback; playback PCM -> OBS mixer; mute verified'

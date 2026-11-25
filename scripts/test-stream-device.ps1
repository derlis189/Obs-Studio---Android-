# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Serial, [switch]$WithCaptures, [string]$SdkRoot)
. (Join-Path $PSScriptRoot 'common.ps1')
$SdkRoot = Resolve-AndroidSdk $SdkRoot
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$node = (Get-Command node -ErrorAction Stop).Source
$receiverRoot = Join-Path $ProjectRoot 'scripts/rtmp-receiver'
if (-not (Test-Path -LiteralPath "$receiverRoot/node_modules/node-media-server")) { throw 'Run npm ci --prefix scripts/rtmp-receiver --ignore-scripts first' }
$run = Join-Path $ProjectRoot "out/phase5/run-$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force -Path $run | Out-Null
$media = Join-Path $run 'received.flv'
$receiver = Start-Process -FilePath $node -ArgumentList @(('"' + "$receiverRoot/receive.cjs" + '"'), ('"' + $media + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/receiver.log" -RedirectStandardError "$run/receiver-errors.log"
try {
    Start-Sleep -Milliseconds 500
    if ($receiver.HasExited) { throw "Receiver failed: $run/receiver-errors.log" }
    $commands = @(@('-s',$Serial,'reverse','tcp:1935','tcp:1935'), @('-s',$Serial,'shell','run-as','org.obsandroid.port','rm','-f','files/stream-probe.json'), @('-s',$Serial,'shell','am','start','-n','org.obsandroid.port/.StudioActivity'))
    $index = 0
    foreach ($command in $commands) {
        $result = Invoke-Recorded $adb $command "$run/setup-$index.log"
        if ($result.exitCode) { throw 'Device setup failed; install/open the debug APK first' }; $index++
    }
    Start-Sleep -Seconds 1
    $arguments = @('-s',$Serial,'shell','am','start','-n','org.obsandroid.port/.StreamProbeActivity')
    if ($WithCaptures) { $arguments += @('--ez','capture','true') }
    $result = Invoke-Recorded $adb $arguments "$run/start-probe.log"
    if ($result.exitCode) { throw 'Cannot launch stream probe' }
    Start-Sleep -Seconds 4
    $result = Invoke-Recorded $adb @('-s',$Serial,'shell','input','keyevent','KEYCODE_HOME') "$run/background.log"
    $deadline = (Get-Date).AddSeconds(40); $json = $null
    do {
        Start-Sleep -Seconds 2
        $raw = @(& $adb -s $Serial shell run-as org.obsandroid.port cat files/stream-probe.json 2>$null)
        if ($LASTEXITCODE -eq 0) { $json = $raw -join "`n"; break }
    } while ((Get-Date) -lt $deadline)
    if (-not $json) { throw "Stream probe timed out: $run" }
    $json | Set-Content "$run/stream-probe.json" -Encoding utf8
    $probe = $json | ConvertFrom-Json
    if (-not $probe.pass) { throw "Android output failed: $run/stream-probe.json" }
    if (-not $receiver.WaitForExit(5000) -or $receiver.ExitCode) { throw "Receiver failed to finish: $run/receiver.log" }
    $remote = '/data/local/tmp/obs-android-port'
    foreach ($command in @(
        @('-s',$Serial,'shell','mkdir','-p',$remote),
        @('-s',$Serial,'push',"$ProjectRoot/out/libobs-arm64/android-native-tests/obs-android-media-probe","$remote/obs-android-media-probe"),
        @('-s',$Serial,'push',$media,"$remote/received.flv"),
        @('-s',$Serial,'shell','chmod','755',"$remote/obs-android-media-probe")
    )) {
        $result = Invoke-Recorded $adb $command "$run/setup-$index.log"; $index++
        if ($result.exitCode) { throw 'Media decoder deployment failed' }
    }
    $result = Invoke-Recorded $adb @('-s',$Serial,'shell',"$remote/obs-android-media-probe","$remote/received.flv") "$run/decoded.json"
    if ($result.exitCode) { throw "Actual stream decoding failed: $run/decoded.json" }
    $decoded = Get-Content "$run/decoded.json" -Raw | ConvertFrom-Json
    if (-not $decoded.pass -or ($WithCaptures -and $decoded.audio_rms -le 0.000001)) { throw 'Decoded content did not satisfy verification criteria' }
    [ordered]@{pass=$true;device=$Serial;withCaptures=[bool]$WithCaptures;selectedCodecs=$probe.selected_codecs;finalOutput=$probe.samples[-1].stream;decoded=$decoded} | ConvertTo-Json -Depth 6 | Set-Content "$run/report.json" -Encoding utf8
    Write-Host "STREAM_PASS real RTMP reception and H264/AAC decode: $run/report.json"
} finally { if (-not $receiver.HasExited) { Stop-Process -Id $receiver.Id } }

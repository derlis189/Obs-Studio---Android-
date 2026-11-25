# SPDX-License-Identifier: GPL-2.0-or-later
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SourceLock = Get-Content -LiteralPath (Join-Path $ProjectRoot 'source-lock.json') -Raw | ConvertFrom-Json
$ObsSource = Join-Path $ProjectRoot $SourceLock.directory

function Resolve-AndroidSdk {
    param([string]$Requested)
    $default = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    foreach ($candidate in @($Requested, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, $default)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        if ($Requested) { throw "Android SDK directory not found: $Requested" }
    }
    throw 'Set ANDROID_SDK_ROOT or pass -SdkRoot.'
}

function Resolve-JavaHome {
    $studio = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
    foreach ($candidate in @($env:JAVA_HOME, $studio)) {
        if (-not $candidate) { continue }
        $release = Join-Path $candidate 'release'
        $java = Join-Path $candidate 'bin/java.exe'
        if ((Test-Path -LiteralPath $java) -and (Test-Path -LiteralPath $release) -and
            (Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="(17|21)(\.|")' -Quiet)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Set JAVA_HOME to JDK 17 or 21.'
}

function Resolve-GitBash {
    $git = (Get-Command git.exe -CommandType Application | Select-Object -First 1).Source
    $installed = Join-Path (Split-Path (Split-Path $git -Parent) -Parent) 'bin/bash.exe'
    if ($env:GIT_BASH -and (Test-Path -LiteralPath $env:GIT_BASH)) { return $env:GIT_BASH }
    if (Test-Path -LiteralPath $installed) { return $installed }
    throw 'Set GIT_BASH to the Git for Windows bash.exe path.'
}

function Invoke-GitChecked {
    param([string[]]$GitArguments)
    $result = @(& git @GitArguments)
    if ($LASTEXITCODE -ne 0) { throw "git failed ($LASTEXITCODE): $GitArguments" }
    return ($result -join "`n").Trim()
}

function Assert-ObsSource {
    $head = Invoke-GitChecked @('-C', $ObsSource, 'rev-parse', 'HEAD')
    if ($head -ne $SourceLock.commit) { throw "OBS commit mismatch: $head" }
    $remote = Invoke-GitChecked @('-C', $ObsSource, 'remote', 'get-url', 'origin')
    if ($remote -ne $SourceLock.repository) { throw "Unexpected OBS remote: $remote" }
    $status = Invoke-GitChecked @('-C', $ObsSource, 'status', '--porcelain', '--untracked-files=all', '--ignore-submodules=all')
    if ($status) { throw "OBS checkout has local modifications; preserve/review them before auditing: $status" }
    foreach ($module in $SourceLock.submodules) {
        $modulePath = Join-Path $ObsSource $module.path
        # A missing .git would make git -C search the parent repository instead.
        if (-not (Test-Path -LiteralPath (Join-Path $modulePath '.git'))) { throw "Missing submodule: $($module.path)" }
        $moduleHead = Invoke-GitChecked @('-C', $modulePath, 'rev-parse', 'HEAD')
        if ($moduleHead -ne $module.commit) { throw "Submodule commit mismatch: $($module.path)" }
        $moduleStatus = Invoke-GitChecked @('-C', $modulePath, 'status', '--porcelain', '--untracked-files=all', '--ignore-submodules=all')
        if ($moduleStatus) { throw "Modified submodule: $($module.path)" }
    }
}

function Invoke-Recorded {
    param([string]$Executable, [string[]]$Arguments, [string]$Log)
    New-Item -ItemType Directory -Path (Split-Path -Parent $Log) -Force | Out-Null
    if (Test-Path -LiteralPath $Log) {
        $previous = "$Log.previous-$(Get-Date -Format yyyyMMdd-HHmmss-fff)"
        Move-Item -LiteralPath $Log -Destination $previous
        if (Test-Path -LiteralPath "$Log.command.json") {
            Move-Item -LiteralPath "$Log.command.json" -Destination "$previous.command.json"
        }
    }
    @{ executable = $Executable; arguments = $Arguments; workingDirectory = (Get-Location).Path } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$Log.command.json" -Encoding utf8
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $Executable @Arguments 2>&1)
        $nativeExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    $output = ($lines | ForEach-Object { "$_" }) -join "`n"
    $output | Set-Content -LiteralPath $Log -Encoding utf8
    Write-Host "[$nativeExitCode] $Log"
    return [pscustomobject]@{ exitCode = $nativeExitCode; output = $output; log = $Log }
}

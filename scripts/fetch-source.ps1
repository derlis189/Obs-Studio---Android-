# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param([switch]$VerifyOnly)
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not $VerifyOnly) {
    if (-not (Test-Path -LiteralPath $ObsSource)) {
        New-Item -ItemType Directory -Path $ObsSource -Force | Out-Null
        Invoke-GitChecked @('init', $ObsSource) | Out-Host
        Invoke-GitChecked @('-C', $ObsSource, 'remote', 'add', 'origin', $SourceLock.repository) | Out-Host
        Invoke-GitChecked @('-C', $ObsSource, 'fetch', '--depth', '1', 'origin', $SourceLock.commit) | Out-Host
        Invoke-GitChecked @('-C', $ObsSource, 'checkout', '--detach', 'FETCH_HEAD') | Out-Host
        Invoke-GitChecked @('-C', $ObsSource, 'submodule', 'update', '--init', '--recursive', '--depth', '1') | Out-Host
    } else {
        Write-Host 'Existing checkout: verifying only; no reset, checkout or overwrite.'
    }
}
Assert-ObsSource
Write-Host "Verified official OBS source and 4 pinned submodules: $($SourceLock.commit)"

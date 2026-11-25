# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-ObsSource
$derived = Join-Path $ProjectRoot 'out/src/obs-studio'
if (-not (Test-Path -LiteralPath $derived)) {
    Invoke-GitChecked @('clone', '--shared', $ObsSource, $derived) | Out-Host
}
$head = Invoke-GitChecked @('-C', $derived, 'rev-parse', 'HEAD')
if ($head -ne $SourceLock.commit) { throw "Derived checkout mismatch: $head" }
$manifestPath = Join-Path $ProjectRoot 'android/source-manifest.json'
if (Test-Path -LiteralPath $manifestPath) {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $matchesManifest = $true
    foreach ($entry in $manifest.files) {
        $path = Join-Path $derived $entry.path
        if (-not (Test-Path -LiteralPath $path)) { $matchesManifest = $false; break }
        $normalized = [System.IO.File]::ReadAllText($path).Replace("`r`n", "`n")
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $actual = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalized))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        if ($actual -ne $entry.sha256Lf) { $matchesManifest = $false; break }
    }
    if ($matchesManifest) { Write-Host "Verified already prepared Android source: $derived"; return }
}
$dirty = Invoke-GitChecked @('-C', $derived, 'status', '--porcelain', '--ignore-submodules=all')
if ($dirty) { throw 'Derived source differs from the recorded Android patches; preserve and review modifications before preparing.' }
foreach ($patch in Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'patches/obs-studio') -Filter '*.patch' | Sort-Object Name) {
    & git -C $derived apply --check $patch.FullName 2>$null
    if ($LASTEXITCODE -eq 0) {
        Invoke-GitChecked @('-C', $derived, 'apply', $patch.FullName) | Out-Host
    } else { throw "Patch does not apply; preserving source: $($patch.Name)" }
}
Write-Host "Prepared OBS Android tree: $derived"

# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')

$source = Join-Path $ProjectRoot 'out/downstream-keyer-src'
$revision = 'b3d1a8b337b83dc9d543f77cbffff237c9b429d0'
if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $source) | Out-Null
    & git clone --branch 0.4.5 --depth 1 https://github.com/exeldro/obs-downstream-keyer.git $source
    if ($LASTEXITCODE) { throw 'Downstream Keyer clone failed' }
}
if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $revision) {
    throw 'Unexpected Downstream Keyer revision.'
}

$patch = Join-Path $ProjectRoot 'patches/downstream-keyer/0001-android-support.patch'
& git -C $source apply --reverse --check $patch 2>$null
if ($LASTEXITCODE) {
    & git -C $source apply --check $patch
    if ($LASTEXITCODE) { throw 'Downstream Keyer Android patch does not apply cleanly.' }
    & git -C $source apply $patch
    if ($LASTEXITCODE) { throw 'Downstream Keyer Android patch failed.' }
}

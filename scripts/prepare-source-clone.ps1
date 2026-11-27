# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')

$source = Join-Path $ProjectRoot 'out/source-clone-src'
$revision = 'e5ca9dd25229fdf1f1b8c6fa83762c0581c18c92'
if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $source) | Out-Null
    & git clone --branch 0.2.3 --depth 1 https://github.com/exeldro/obs-source-clone.git $source
    if ($LASTEXITCODE) { throw 'Source Clone clone failed' }
}
if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $revision) {
    throw 'Unexpected Source Clone revision.'
}

$patch = Join-Path $ProjectRoot 'patches/source-clone/0001-android-support.patch'
& git -C $source apply --reverse --check $patch 2>$null
if ($LASTEXITCODE) {
    & git -C $source apply --check $patch
    if ($LASTEXITCODE) { throw 'Source Clone Android patch does not apply cleanly.' }
    & git -C $source apply $patch
    if ($LASTEXITCODE) { throw 'Source Clone Android patch failed.' }
}

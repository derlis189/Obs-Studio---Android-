# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')

$source = Join-Path $ProjectRoot 'out/composite-blur-src'
$revision = '8483ccbf51ef633b59341904be02497a9b07dcef'
if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $source) | Out-Null
    & git clone --branch v1.6.1 --depth 1 https://github.com/FiniteSingularity/obs-composite-blur.git $source
    if ($LASTEXITCODE) { throw 'Composite Blur clone failed' }
}
if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $revision) {
    throw 'Unexpected Composite Blur revision.'
}

$patch = Join-Path $ProjectRoot 'patches/composite-blur/0001-android-support.patch'
& git -C $source apply --reverse --check $patch 2>$null
if ($LASTEXITCODE) {
    & git -C $source apply --check $patch
    if ($LASTEXITCODE) { throw 'Composite Blur Android patch does not apply cleanly.' }
    & git -C $source apply $patch
    if ($LASTEXITCODE) { throw 'Composite Blur Android patch failed.' }
}

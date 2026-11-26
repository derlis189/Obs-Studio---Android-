# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')

$source = Join-Path $ProjectRoot 'out/stroke-glow-shadow-src'
$revision = '6742704be21538a66fe15142162e59dac6eee76e'
if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $source) | Out-Null
    & git clone --branch v1.6.1 --depth 1 https://github.com/FiniteSingularity/obs-stroke-glow-shadow.git $source
    if ($LASTEXITCODE) { throw 'Stroke Glow Shadow clone failed' }
}
if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $revision) {
    throw 'Unexpected Stroke Glow Shadow revision.'
}

$patch = Join-Path $ProjectRoot 'patches/stroke-glow-shadow/0001-android-support.patch'
& git -C $source apply --reverse --check $patch 2>$null
if ($LASTEXITCODE) {
    & git -C $source apply --check $patch
    if ($LASTEXITCODE) { throw 'Stroke Glow Shadow Android patch does not apply cleanly.' }
    & git -C $source apply $patch
    if ($LASTEXITCODE) { throw 'Stroke Glow Shadow Android patch failed.' }
}

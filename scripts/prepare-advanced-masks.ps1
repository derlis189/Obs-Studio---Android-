# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')

$source = Join-Path $ProjectRoot 'out/advanced-masks-src'
$revision = '8c888208162d9e271b10931ef29e45e871210ec0'
if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $source) | Out-Null
    & git clone --branch v1.6.1 --depth 1 https://github.com/FiniteSingularity/obs-advanced-masks.git $source
    if ($LASTEXITCODE) { throw 'Advanced Masks clone failed' }
}
if ((Invoke-GitChecked @('-C', $source, 'rev-parse', 'HEAD')) -ne $revision) {
    throw 'Unexpected Advanced Masks revision.'
}

$patch = Join-Path $ProjectRoot 'patches/advanced-masks/0001-android-support.patch'
& git -C $source apply --reverse --check $patch 2>$null
if ($LASTEXITCODE) {
    & git -C $source apply --check $patch
    if ($LASTEXITCODE) { throw 'Advanced Masks Android patch does not apply cleanly.' }
    & git -C $source apply $patch
    if ($LASTEXITCODE) { throw 'Advanced Masks Android patch failed.' }
}

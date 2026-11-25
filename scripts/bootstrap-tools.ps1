# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
$version = '3.30.8'
$archiveName = "cmake-$version-windows-x86_64.zip"
$expectedHash = 'ec0de3ecbc1e02ed0304d42dccdbf8f36b705af74f1783b07d612b4a02ae2808'
$downloads = Join-Path $ProjectRoot 'tools/downloads'
$archive = Join-Path $downloads $archiveName
$destination = Join-Path $ProjectRoot "tools/cmake-$version-windows-x86_64"
New-Item -ItemType Directory -Force -Path $downloads | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri "https://github.com/Kitware/CMake/releases/download/v$version/$archiveName" -OutFile $archive
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash -ne $expectedHash) {
    throw 'CMake SHA256 mismatch. Archive preserved for inspection; do not execute it.'
}
if (-not (Test-Path -LiteralPath (Join-Path $destination 'bin/cmake.exe'))) {
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $ProjectRoot 'tools')
}
& (Join-Path $destination 'bin/cmake.exe') --version
if ($LASTEXITCODE -ne 0) { throw 'CMake cannot run' }
Write-Host "Verified archive SHA256: $expectedHash"

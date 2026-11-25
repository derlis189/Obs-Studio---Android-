# SPDX-License-Identifier: GPL-2.0-or-later
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'common.ps1')
$lock = Get-Content -LiteralPath (Join-Path $ProjectRoot 'dependencies-lock.json') -Raw | ConvertFrom-Json
$vlc = $lock.libvlc
$destination = Join-Path $ProjectRoot 'out/deps/libvlc'
New-Item -ItemType Directory -Force -Path $destination | Out-Null

function Get-VerifiedArchive {
    param([string]$Url, [string]$Sha256)
    $path = Join-Path $destination ([IO.Path]::GetFileName(([Uri]$Url).AbsolutePath))
    if (-not (Test-Path -LiteralPath $path)) {
        $temporary = "$path.part"
        Invoke-WebRequest -Uri $Url -OutFile $temporary
        if ((Get-FileHash -LiteralPath $temporary).Hash -ine $Sha256) {
            throw "Downloaded archive checksum mismatch: $temporary"
        }
        Move-Item -LiteralPath $temporary -Destination $path
    }
    if ((Get-FileHash -LiteralPath $path).Hash -ine $Sha256) {
        throw "Cached archive checksum mismatch: $path"
    }
    return $path
}

$aar = Get-VerifiedArchive $vlc.runtimeUrl $vlc.runtimeSha256
$headers = Get-VerifiedArchive $vlc.headersUrl $vlc.headersSha256
$runtime = "jni/$($lock.abi)/libvlc.so"
$extracted = Join-Path $destination 'extracted'
$include = Join-Path $ProjectRoot "out/deps/$($lock.abi)/include"
New-Item -ItemType Directory -Force -Path $extracted, $include | Out-Null
& tar.exe -xf $aar -C $extracted $runtime
if ($LASTEXITCODE -ne 0) { throw 'LibVLC runtime extraction failed.' }
& tar.exe -xf $headers -C $destination $vlc.headersDirectory
if ($LASTEXITCODE -ne 0) { throw 'LibVLC header extraction failed.' }
Copy-Item -LiteralPath (Join-Path $destination $vlc.headersDirectory) -Destination $include -Recurse -Force

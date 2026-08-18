<#
.SYNOPSIS
    Builds from source and produces AynDualScreen-SkyrimSE.zip, laid out to drop into Data.

.DESCRIPTION
    Always from a fresh build, never from whatever happens to be in dist\ -- a release built from a
    stale folder is the kind of mistake nobody catches until someone reports a bug that was fixed
    weeks ago.

    REMEMBER THE RELEASE RULE. See CONTRIBUTING.md: every GitHub release must carry ALL of the
    stable asset names, because the README links to releases/latest/download/<name> and a release
    that ships only the project you changed becomes "latest" and breaks every other link at once.

        AynDualScreen-Stardew.zip
        AynDualScreen-Terraria.tmod
        AynDualScreen-Minecraft-mc1.21.1.jar
        AynDualScreen-FalloutNV.zip
        AynDualScreen-SkyrimSE.zip      <- this one
        AynDualScreen-App.apk

    Pull the unchanged ones off the previous release with `gh release download <tag>` and upload
    them again with the new one. It is cheap, and it is the whole reason the links keep working.
#>

[CmdletBinding()]
param(
    # Skip the build and package what is already in dist\. For testing this script only.
    [switch] $NoBuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$zip = Join-Path $root 'AynDualScreen-SkyrimSE.zip'

if (-not $NoBuild) {
    & (Join-Path $root 'build.ps1') -NoInstall
    if ($LASTEXITCODE -ne 0) { throw "Build failed; nothing packaged." }
}

$staging = Join-Path $root 'dist'
if (-not (Test-Path (Join-Path $staging 'Data\SKSE\Plugins\AynDualScreen.dll'))) {
    throw "dist\ does not contain a built plugin."
}

# The readme goes in the zip too. Somebody who downloads a mod six months from now has the file and
# nothing else -- the install steps have to travel with it.
Copy-Item (Join-Path $root 'README.md') $staging -Force

if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zip -CompressionLevel Optimal

$size = [math]::Round((Get-Item $zip).Length / 1KB)
Write-Host "Packaged $zip ($size KB)" -ForegroundColor Green
Write-Host ""
Write-Host "Before you publish: the release needs all six assets, not just this one." -ForegroundColor Yellow

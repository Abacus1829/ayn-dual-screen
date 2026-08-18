<#
.SYNOPSIS
    Clones vcpkg into extern/ and registers the CommonLibSSE-NG port, which build.ps1 then uses.

.DESCRIPTION
    Nothing is vendored in this repository. CommonLibSSE-NG is a separate project under its own
    licence, and the same reasoning applies as to the xNVSE SDK the Fallout: New Vegas mod fetches
    rather than ships: it is not ours to redistribute, and a vendored copy goes stale silently.

    This is the equivalent of falloutnv/fetch-nvse.ps1. Run it once.

    The registry baseline is resolved when you run this rather than pinned in vcpkg.json, and the
    resolved values are written into vcpkg-configuration.json. That file is not committed, so
    everyone's build is reproducible for them without this repository carrying a hash it cannot
    verify.
#>

[CmdletBinding()]
param(
    # Fetch again even if extern/vcpkg is already there.
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$vcpkg = Join-Path $root 'extern\vcpkg'

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "Git is not installed, and it is how vcpkg and CommonLibSSE-NG are fetched. Install it from https://git-scm.com/ and run this again."
}

if ((Test-Path $vcpkg) -and $Force) {
    Write-Host "Removing the existing extern\vcpkg..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force $vcpkg
}

if (-not (Test-Path $vcpkg)) {
    Write-Host "Cloning vcpkg (this is a few hundred megabytes)..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force (Join-Path $root 'extern') | Out-Null
    git clone --depth 1 https://github.com/microsoft/vcpkg.git $vcpkg
} else {
    Write-Host "extern\vcpkg is already there. Pass -Force to fetch it again." -ForegroundColor DarkGray
}

$bootstrap = Join-Path $vcpkg 'bootstrap-vcpkg.bat'
$exe = Join-Path $vcpkg 'vcpkg.exe'

if (-not (Test-Path $exe)) {
    Write-Host "Bootstrapping vcpkg..." -ForegroundColor Cyan
    & $bootstrap -disableMetrics
    if ($LASTEXITCODE -ne 0) { throw "vcpkg failed to bootstrap." }
}

# CommonLibSSE-NG itself, from source.
#
# NOT from a vcpkg port. The registries publish it at 3.7.0 -- the May 2023 upstream -- and
# upstream stopped in 2024, before Skyrim 1.6.1130 and 1.6.1170 shipped. A plugin built against
# that loads on 1.6.1170 and then hangs the game the first time it reads the player, because an
# address resolved for a runtime the library never saw is a jump into arbitrary code. That is not
# a hypothetical; it is what this project did.
#
# alandtse/CommonLibSSE-NG is the maintained fork, and it knows the current runtimes.
$commonlib = Join-Path $root 'extern\CommonLibSSE-NG'

if ((Test-Path $commonlib) -and $Force) {
    Write-Host "Removing the existing extern\CommonLibSSE-NG..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force $commonlib
}

if (-not (Test-Path $commonlib)) {
    Write-Host "Cloning CommonLibSSE-NG (the maintained fork)..." -ForegroundColor Cyan
    git clone --depth 1 --branch ng https://github.com/alandtse/CommonLibSSE-NG.git $commonlib
    if ($LASTEXITCODE -ne 0) { throw "Could not clone CommonLibSSE-NG." }
} else {
    Write-Host "extern\CommonLibSSE-NG is already there. Pass -Force to fetch it again." -ForegroundColor DarkGray
}

# A leftover from when this project took CommonLibSSE from a registry. If it stays, vcpkg reads it
# and goes looking for a port we no longer use.
$stale = Join-Path $root 'vcpkg-configuration.json'
if (Test-Path $stale) {
    Remove-Item $stale -Force
    Write-Host "Removed the stale vcpkg-configuration.json." -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "Done. Next: .\build.ps1" -ForegroundColor Green

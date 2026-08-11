<#
    Clones the xNVSE SDK into extern\nvse, which the project builds against.

    xNVSE is not vendored into this repository: it is somebody else's project under its own
    licence, and pinning a copy here would go stale. This fetches it instead.

        .\fetch-nvse.ps1              # clone or update
        .\fetch-nvse.ps1 -Ref xnvse_6_3_5   # pin to a tag
#>

[CmdletBinding()]
param(
    [string] $Ref = "",
    [string] $Destination = "$PSScriptRoot\extern\nvse",
    [string] $Repository = "https://github.com/xNVSE/NVSE.git"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "git is not on PATH. Install Git for Windows, or clone $Repository into $Destination by hand."
}

if (Test-Path "$Destination\.git") {
    Write-Host "Updating the xNVSE checkout at $Destination"
    git -C $Destination fetch --tags --quiet
    if ($Ref) { git -C $Destination checkout --quiet $Ref }
    else      { git -C $Destination pull --quiet --ff-only }
}
else {
    New-Item -ItemType Directory -Force -Path (Split-Path $Destination) | Out-Null
    Write-Host "Cloning xNVSE into $Destination"
    if ($Ref) { git clone --quiet --branch $Ref --depth 1 $Repository $Destination }
    else      { git clone --quiet --depth 1 $Repository $Destination }
}

$probe = Join-Path $Destination "nvse\nvse\PluginAPI.h"
if (-not (Test-Path $probe)) {
    throw "The checkout finished but $probe is missing. Something is wrong with $Destination."
}

$head = (git -C $Destination rev-parse --short HEAD).Trim()
Write-Host "xNVSE SDK ready at $Destination (at $head)."
Write-Host "Now run: .\build.ps1"

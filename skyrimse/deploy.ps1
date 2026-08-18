<#
.SYNOPSIS
    Pushes web/ (and optionally the built DLL) into an existing install, without a rebuild.

.DESCRIPTION
    For iterating on the second screen while the game is running. A CSS change needs no compiler,
    and stopping Skyrim to see one is the reason this exists.

    Better still: set WebRootOverride in AynDualScreen.ini to this project's web folder, and the
    plugin reads the files off disk on every request -- then a refresh is the whole loop and you do
    not need this script at all.

.EXAMPLE
    .\deploy.ps1
    .\deploy.ps1 -IncludeDll
#>

[CmdletBinding()]
param(
    [string] $GamePath,
    [switch] $IncludeDll
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not $GamePath) {
    $candidates = @()
    foreach ($drive in (Get-PSDrive -PSProvider FileSystem)) {
        $candidates += @(
            "$($drive.Root)SteamLibrary\steamapps\common\Skyrim Special Edition"
            "$($drive.Root)Program Files (x86)\Steam\steamapps\common\Skyrim Special Edition"
        )
    }
    $GamePath = $candidates | Where-Object { Test-Path (Join-Path $_ 'SkyrimSE.exe') } | Select-Object -First 1
}

if (-not $GamePath) { throw "No Skyrim SE install found. Pass -GamePath." }

$target = Join-Path $GamePath 'Data\SKSE\Plugins\AynDualScreen\web'
New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item (Join-Path $root 'web\*') $target -Recurse -Force
Write-Host "Pushed web/ to $target" -ForegroundColor Green

if ($IncludeDll) {
    $dll = Get-ChildItem -Path (Join-Path $root 'build') -Filter 'AynDualScreen.dll' -Recurse |
        Select-Object -First 1
    if (-not $dll) { throw "No built DLL. Run .\build.ps1 first." }

    # The DLL is loaded and locked while the game is running, so this fails with a file-in-use
    # error rather than silently doing nothing. That is the correct outcome: a plugin cannot be
    # swapped under a running Skyrim.
    Copy-Item $dll.FullName (Join-Path $GamePath 'Data\SKSE\Plugins') -Force
    Write-Host "Pushed the DLL. Restart the game for it to take effect." -ForegroundColor Green
}

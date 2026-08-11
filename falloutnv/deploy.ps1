<#
    Deploys the mod to Mod Organizer 2 (or a plain Data folder) without rebuilding.

        .\deploy.ps1              # web files only -- no game restart needed
        .\deploy.ps1 -Dll         # also swap the DLL (the game must be closed)

    WHY THIS EXISTS

    web/ is copied into build\Release\package as part of the build. Deploying that package after
    editing web/ therefore pushes whatever the UI looked like at BUILD time, silently reverting
    newer changes -- which is exactly how a set of hand-tuned figure coordinates got thrown away
    minutes after being applied.

    So this always deploys web/ from source, and refreshes the package copy on the way past, which
    means the two can no longer drift.
#>

[CmdletBinding()]
param(
    [switch] $Dll,
    [string] $Target = "$env:LOCALAPPDATA\ModOrganizer\New Vegas\mods\Ayn Dual Screen\NVSE\Plugins"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$web = Join-Path $root "web"
$package = Join-Path $root "build\Release\package"

if (-not (Test-Path $Target)) {
    throw "Target not found: $Target`nPass -Target <your Data\NVSE\Plugins path>."
}

# Keep the package's copy in step, so a later -Dll deploy cannot resurrect an old UI.
$packageWeb = Join-Path $package "AynDualScreen\web"
if (Test-Path $packageWeb) {
    Copy-Item "$web\*" -Destination $packageWeb -Recurse -Force
}

Copy-Item "$web\*" -Destination (Join-Path $Target "AynDualScreen\web") -Recurse -Force
Write-Host "Deployed web/ -> $Target"

if ($Dll) {
    $dll = Join-Path $package "AynDualScreen.dll"
    if (-not (Test-Path $dll)) { throw "No built DLL at $dll. Run build.ps1 first." }
    try {
        Copy-Item $dll -Destination $Target -Force
        Write-Host "Deployed AynDualScreen.dll"
    }
    catch {
        # The usual cause by far, and the message Windows gives is not obvious about it.
        Write-Warning "Could not replace the DLL -- is the game still running? It holds the file open while loaded."
    }
}

Write-Host "`nReload the second screen. Only a DLL swap needs the game restarted."

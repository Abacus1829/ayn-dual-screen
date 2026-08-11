<#
    Adds the two Visual Studio components this project needs and that a default install leaves out:
    the 32-bit MSVC toolset, and a Windows SDK.

        .\install-toolchain.ps1

    This asks for administrator rights, because modifying a Visual Studio install needs them. It
    downloads a few GB and takes a while. Nothing here touches the game or this repository -- it
    only adds components to Visual Studio.

    You can do exactly the same thing by hand: open the Visual Studio Installer, press Modify on
    Visual Studio 2022, go to Individual Components, and tick
      - MSVC v143 - VS 2022 C++ x64/x86 build tools (Latest)
      - Windows 11 SDK (any version)
#>

[CmdletBinding()]
param(
    [switch] $WhatIfOnly
)

$ErrorActionPreference = "Stop"

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$setup   = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\setup.exe"

if (-not (Test-Path $vswhere) -or -not (Test-Path $setup)) {
    throw "The Visual Studio Installer isn't on this machine. Install Visual Studio 2022 Community first: https://visualstudio.microsoft.com/downloads/"
}

$installPath = & $vswhere -latest -property installationPath
if (-not $installPath) { throw "vswhere found no Visual Studio installation to modify." }

$components = @(
    "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
    "Microsoft.VisualStudio.Component.Windows11SDK.22621"
)

$arguments = @("modify", "--installPath", "`"$installPath`"")
foreach ($c in $components) { $arguments += @("--add", $c) }
$arguments += @("--passive", "--norestart")

Write-Host "Will modify: $installPath"
Write-Host "Adding:"
$components | ForEach-Object { Write-Host "  $_" }

if ($WhatIfOnly) { Write-Host "`n-WhatIfOnly given; stopping here."; return }

Write-Host "`nA User Account Control prompt is about to appear - approve it to continue."

# -Verb RunAs is what raises the UAC prompt; without it the modify silently does nothing, which is
# a genuinely confusing way to fail.
$process = Start-Process -FilePath $setup -ArgumentList $arguments -Verb RunAs -PassThru -Wait

if ($process.ExitCode -eq 0) {
    Write-Host "`nDone. Now run: .\build.ps1"
}
elseif ($process.ExitCode -eq 3010) {
    Write-Host "`nDone, but Windows wants a restart first. Reboot, then run: .\build.ps1"
}
else {
    Write-Warning "The installer exited with code $($process.ExitCode). Open the Visual Studio Installer and add the two components listed above by hand."
}

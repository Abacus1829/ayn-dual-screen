<#
    Builds the plugin and, if it can find the game, installs it.

        .\build.ps1                 # Release, then install if New Vegas is found
        .\build.ps1 -Configuration Debug
        .\build.ps1 -NoInstall
        .\build.ps1 -GamePath "<your Steam library>\steamapps\common\Fallout New Vegas"

    New Vegas is a 32-bit process, so this always builds Win32. There is no x64 configuration;
    an x64 DLL is one the game cannot load.
#>

[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")] [string] $Configuration = "Release",
    [string] $GamePath = "",
    [string] $NvseDir = "",
    [switch] $NoInstall
)

$ErrorActionPreference = "Stop"

# ── the SDK ─────────────────────────────────────────────────────────────────

if (-not $NvseDir) { $NvseDir = "$PSScriptRoot\extern\nvse" }

if (-not (Test-Path "$NvseDir\nvse\nvse\PluginAPI.h")) {
    Write-Host "The xNVSE SDK isn't here yet; fetching it."
    & "$PSScriptRoot\fetch-nvse.ps1" -Destination $NvseDir
}

# ── the compiler ────────────────────────────────────────────────────────────

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) {
    throw "Visual Studio Installer not found. Install Visual Studio 2022 (Community is fine) with the 'Desktop development with C++' workload."
}

$msbuild = & $vswhere -latest -requires Microsoft.Component.MSBuild `
    -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1

if (-not $msbuild) {
    throw "MSBuild not found. Open the Visual Studio Installer and add the 'Desktop development with C++' workload."
}

# The 32-bit toolset and a Windows SDK are separate components, and their absence shows up as a
# baffling 'cannot open include file <windows.h>' rather than anything useful. Say so up front.
$vsRoot = & $vswhere -latest -property installationPath
$hasX86 = Get-ChildItem "$vsRoot\VC\Tools\MSVC\*\bin\Hostx64\x86\cl.exe" -ErrorAction SilentlyContinue
$hasSdk = Test-Path "${env:ProgramFiles(x86)}\Windows Kits\10\Include"

if (-not $hasX86 -or -not $hasSdk) {
    Write-Warning @"
This machine is missing part of the C++ toolchain:
  32-bit MSVC toolset : $(if ($hasX86) { 'present' } else { 'MISSING' })
  Windows 10/11 SDK   : $(if ($hasSdk) { 'present' } else { 'MISSING' })

Fix it with:   .\install-toolchain.ps1
(or by hand: Visual Studio Installer -> Modify -> Individual Components -> tick
 'MSVC v143 - VS 2022 C++ x64/x86 build tools' and a 'Windows 11 SDK'.)
"@
}

# ── build ───────────────────────────────────────────────────────────────────

Write-Host "Building $Configuration|Win32..."

& $msbuild "$PSScriptRoot\AynDualScreen.vcxproj" `
    /nologo /verbosity:minimal `
    /p:Configuration=$Configuration `
    /p:Platform=Win32 `
    /p:NvseDir=$NvseDir

if ($LASTEXITCODE -ne 0) { throw "Build failed." }

$package = "$PSScriptRoot\build\$Configuration\package"
Write-Host "Built. Package is at $package"

if ($NoInstall) { return }

# ── install ─────────────────────────────────────────────────────────────────

if (-not $GamePath) {
    $candidates = @(
        "${env:ProgramFiles(x86)}\Steam\steamapps\common\Fallout New Vegas",
        "C:\GOG Games\Fallout New Vegas"
    )
    # Steam libraries elsewhere on the machine.
    foreach ($drive in (Get-PSDrive -PSProvider FileSystem)) {
        $candidates += "$($drive.Root)SteamLibrary\steamapps\common\Fallout New Vegas"
    }
    $GamePath = $candidates | Where-Object { Test-Path (Join-Path $_ "FalloutNV.exe") } | Select-Object -First 1
}

if (-not $GamePath) {
    Write-Warning "Couldn't find New Vegas. Copy the contents of $package into Data\NVSE\Plugins yourself, or re-run with -GamePath."
    return
}

$plugins = Join-Path $GamePath "Data\NVSE\Plugins"
New-Item -ItemType Directory -Force -Path $plugins | Out-Null
Copy-Item "$package\*" -Destination $plugins -Recurse -Force

Write-Host "Installed to $plugins"

if (-not (Test-Path (Join-Path $GamePath "nvse_loader.exe"))) {
    Write-Warning "xNVSE itself does not look installed here - there is no nvse_loader.exe next to FalloutNV.exe. Get it from https://github.com/xNVSE/NVSE/releases and launch the game through it, or the plugin will never load."
}

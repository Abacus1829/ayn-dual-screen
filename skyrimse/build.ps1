<#
.SYNOPSIS
    Builds the SKSE plugin, lays the mod out the way it installs, and copies it into Skyrim.

.DESCRIPTION
    Checks the toolchain first and says which piece is missing, because the failure otherwise shows
    up as a baffling "cannot open include file" rather than "you did not install the C++ workload".

    The build is x64. SkyrimSE.exe is 64-bit; a 32-bit DLL is one the game physically cannot load,
    and it fails silently rather than with an error, so there is no Win32 configuration on purpose.

.EXAMPLE
    .\build.ps1
    .\build.ps1 -NoInstall
    .\build.ps1 -GamePath "D:\SteamLibrary\steamapps\common\Skyrim Special Edition"
#>

[CmdletBinding()]
param(
    # Build without copying anything into the game.
    [switch] $NoInstall,

    # Skip the search and use this install.
    [string] $GamePath,

    [ValidateSet('Release', 'Debug')]
    [string] $Configuration = 'Release'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# ── toolchain ───────────────────────────────────────────────────────────────

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) {
    throw @"
Visual Studio 2022 is not installed.

Install it with the 'Desktop development with C++' workload:
    winget install --id Microsoft.VisualStudio.2022.Community --override "--add Microsoft.VisualStudio.Workload.NativeDesktop --includeRecommended --quiet"
"@
}

$vsPath = & $vswhere -latest -products * `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
    -property installationPath

if (-not $vsPath) {
    throw @"
Visual Studio is installed, but without the x64 C++ build tools.

That is the component people are missing, and its absence shows up as a missing <windows.h>
rather than as anything useful. Add it from the Visual Studio Installer:
    Workloads -> Desktop development with C++
"@
}

Write-Host "Visual Studio: $vsPath" -ForegroundColor DarkGray

# CMake ships with the C++ workload, so prefer that one over anything on PATH -- it is the version
# the toolchain was tested with.
$cmake = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
if (-not (Test-Path $cmake)) {
    $cmake = (Get-Command cmake -ErrorAction SilentlyContinue).Source
}
if (-not $cmake) {
    throw "No CMake. It comes with the C++ workload -- add 'C++ CMake tools for Windows' in the Visual Studio Installer."
}

if (-not (Test-Path (Join-Path $root 'extern\vcpkg\vcpkg.exe'))) {
    throw "The dependencies are not fetched yet. Run .\fetch-deps.ps1 first."
}

# ── build ───────────────────────────────────────────────────────────────────

$preset = if ($Configuration -eq 'Debug') { 'debug' } else { 'release' }

Write-Host ""
Write-Host "Configuring ($Configuration)..." -ForegroundColor Cyan
& $cmake --preset $preset
if ($LASTEXITCODE -ne 0) { throw "CMake configure failed." }

Write-Host ""
Write-Host "Building. The first one builds CommonLibSSE too and takes a while." -ForegroundColor Cyan
& $cmake --build --preset $preset
if ($LASTEXITCODE -ne 0) { throw "Build failed." }

$buildDir = if ($Configuration -eq 'Debug') { 'build-debug' } else { 'build' }
$dll = Get-ChildItem -Path (Join-Path $root $buildDir) -Filter 'AynDualScreen.dll' -Recurse |
    Select-Object -First 1

if (-not $dll) { throw "The build reported success but produced no DLL." }
Write-Host "Built $($dll.FullName) ($([math]::Round($dll.Length / 1KB)) KB)" -ForegroundColor Green

# ── lay it out ──────────────────────────────────────────────────────────────

$staging = Join-Path $root 'dist\Data\SKSE\Plugins'
if (Test-Path (Join-Path $root 'dist')) { Remove-Item -Recurse -Force (Join-Path $root 'dist') }
New-Item -ItemType Directory -Force (Join-Path $staging 'AynDualScreen\web') | Out-Null

Copy-Item $dll.FullName $staging
Copy-Item (Join-Path $root 'web\*') (Join-Path $staging 'AynDualScreen\web') -Recurse

Write-Host "Laid out in dist\ the way it installs." -ForegroundColor Green

if ($NoInstall) { return }

# ── find the game ───────────────────────────────────────────────────────────

if (-not $GamePath) {
    $candidates = @()
    foreach ($drive in (Get-PSDrive -PSProvider FileSystem)) {
        $candidates += @(
            "$($drive.Root)SteamLibrary\steamapps\common\Skyrim Special Edition"
            "$($drive.Root)Program Files (x86)\Steam\steamapps\common\Skyrim Special Edition"
            "$($drive.Root)Games\Skyrim Special Edition"
        )
    }
    $GamePath = $candidates | Where-Object { Test-Path (Join-Path $_ 'SkyrimSE.exe') } | Select-Object -First 1
}

if (-not $GamePath -or -not (Test-Path (Join-Path $GamePath 'SkyrimSE.exe'))) {
    Write-Warning "No Skyrim SE install found. The build is in dist\ -- copy its Data folder over the game's."
    return
}

Write-Host "Installing into $GamePath" -ForegroundColor Cyan
Copy-Item (Join-Path $root 'dist\Data') $GamePath -Recurse -Force

if (-not (Test-Path (Join-Path $GamePath 'skse64_loader.exe'))) {
    Write-Warning @"
SKSE is not installed there.

The plugin is loaded by skse64_loader.exe; launching SkyrimSE.exe directly runs nothing of this.
Get it from https://skse.silverlock.org/ and launch the game through the loader.
"@
}

Write-Host ""
Write-Host "Done. Launch through skse64_loader.exe and check AynDualScreen.log for the address." -ForegroundColor Green

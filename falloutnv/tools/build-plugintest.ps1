<#
    Builds the harness that loads the real DLL and drives it without New Vegas.

    Must be x86, because the plugin is x86 and a process can only load a DLL of its own
    architecture. That is the opposite of build-assetdump.ps1, which is x64 because it compiles
    the format code directly rather than loading anything.

        tools\build-plugintest.ps1
        build\plugintest.exe build\Release\AynDualScreen.dll
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vsRoot = & $vswhere -latest -property installationPath

# Host x64, target x86.
$cl = Get-ChildItem "$vsRoot\VC\Tools\MSVC\*\bin\Hostx64\x86\cl.exe" | Select-Object -First 1
if (-not $cl) { throw "The 32-bit MSVC toolset is missing. Run install-toolchain.ps1." }

$msvc = Split-Path (Split-Path (Split-Path (Split-Path $cl.FullName -Parent) -Parent) -Parent) -Parent
$sdkRoot = "${env:ProgramFiles(x86)}\Windows Kits\10"
$sdkVer = (Get-ChildItem "$sdkRoot\Include" | Sort-Object Name -Descending | Select-Object -First 1).Name

$env:INCLUDE = "$msvc\include;$sdkRoot\Include\$sdkVer\ucrt;$sdkRoot\Include\$sdkVer\shared;$sdkRoot\Include\$sdkVer\um"
$env:LIB = "$msvc\lib\x86;$sdkRoot\Lib\$sdkVer\ucrt\x86;$sdkRoot\Lib\$sdkVer\um\x86"

$out = Join-Path $root "build"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Push-Location $out
try {
    & $cl.FullName /nologo /EHsc /std:c++17 /O2 /MD "$root\tools\plugintest.cpp" /Fe:plugintest.exe
    if ($LASTEXITCODE -ne 0) { throw "compile failed" }
}
finally { Pop-Location }

Write-Host "Built $out\plugintest.exe"

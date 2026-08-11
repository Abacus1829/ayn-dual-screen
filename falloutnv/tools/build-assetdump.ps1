<#
    Builds the format-code test harness.

    Bsa.cpp, Dds.cpp and Png.cpp have no game dependency -- they are file-format code -- so they
    compile on their own and can be exercised against the real archives without launching New
    Vegas. This builds them as a plain console exe for exactly that.

    Built x64 because it is only a test tool; the plugin itself is Win32 and always will be.

        tools\build-assetdump.ps1
#>

[CmdletBinding()]
param([ValidateSet("Debug", "Release")] [string] $Configuration = "Release")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vsRoot = & $vswhere -latest -property installationPath
$cl = Get-ChildItem "$vsRoot\VC\Tools\MSVC\*\bin\Hostx64\x64\cl.exe" | Select-Object -First 1
if (-not $cl) { throw "cl.exe not found. Run install-toolchain.ps1 first." }

# cl needs INCLUDE and LIB set; the surrounding VS environment normally does that.
# cl.exe lives at <toolset>\bin\Hostx64\x64\cl.exe, so the toolset root is four levels up.
$msvc = Split-Path (Split-Path (Split-Path (Split-Path $cl.FullName -Parent) -Parent) -Parent) -Parent
$sdkRoot = "${env:ProgramFiles(x86)}\Windows Kits\10"
$sdkVer = (Get-ChildItem "$sdkRoot\Include" | Sort-Object Name -Descending | Select-Object -First 1).Name

$env:INCLUDE = "$msvc\include;$sdkRoot\Include\$sdkVer\ucrt;$sdkRoot\Include\$sdkVer\shared;$sdkRoot\Include\$sdkVer\um"
$env:LIB = "$msvc\lib\x64;$sdkRoot\Lib\$sdkVer\ucrt\x64;$sdkRoot\Lib\$sdkVer\um\x64"

$out = Join-Path $root "build"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$flags = if ($Configuration -eq "Debug") { @("/Od", "/Zi", "/MDd") } else { @("/O2", "/MD") }

Push-Location $out
try {
    & $cl.FullName /nologo /EHsc /std:c++17 @flags `
        "$root\tools\assetdump.cpp" "$root\src\Bsa.cpp" "$root\src\Dds.cpp" "$root\src\Png.cpp" `
        /Fe:assetdump.exe
    if ($LASTEXITCODE -ne 0) { throw "compile failed" }
}
finally { Pop-Location }

Write-Host "Built $out\assetdump.exe"

<#
    Assembles the release archive -- the thing people download and drop into their game.

        .\package-release.ps1                    # build\AynDualScreen-FalloutNV.zip
        .\package-release.ps1 -OutDir D:\somewhere

    WHY THIS EXISTS

    The published zip used to be assembled by hand, and it drifted: by the time anyone noticed, the
    download carried a DLL and a UI several builds behind what the repository said it shipped. A
    stale release is worse than no release, because it fails in ways the source cannot explain.

    So the layout lives here rather than in someone's memory. Two rules it exists to enforce:

      * web/ is taken from SOURCE, never from build\Release\package. That package folder holds
        whatever the UI looked like at BUILD time, and shipping it silently reverts newer work --
        the same trap deploy.ps1 was written to close.

      * The DLL is only ever taken from a build, and the script refuses to run if there isn't one.
        It will not quietly ship yesterday's binary.

    The archive is shaped so it can be installed by a mod manager or unpacked straight into Data:

        NVSE\Plugins\AynDualScreen.dll
        NVSE\Plugins\AynDualScreen\LICENSE.txt
        NVSE\Plugins\AynDualScreen\web\...
#>

[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")] [string] $Configuration = "Release",
    [string] $OutDir = "$PSScriptRoot\build"
)

$ErrorActionPreference = "Stop"

$root    = $PSScriptRoot
$web     = Join-Path $root "web"
$dll     = Join-Path $root "build\$Configuration\AynDualScreen.dll"
$license = Join-Path (Split-Path $root -Parent) "LICENSE.txt"

if (-not (Test-Path $dll)) {
    throw "No $Configuration build at $dll. Run .\build.ps1 first -- this script will not ship a binary it did not just see built."
}
if (-not (Test-Path $license)) { throw "LICENSE.txt not found at $license" }

# Staged in a scratch tree rather than zipped in place, so the archive contains exactly what is
# listed below and nothing that happens to be sitting in build\.
$stage = Join-Path $env:TEMP "aynds-fnv-release-$PID"
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
$plugins = New-Item -ItemType Directory -Force -Path (Join-Path $stage "NVSE\Plugins")
$modDir  = New-Item -ItemType Directory -Force -Path (Join-Path $plugins "AynDualScreen")
New-Item -ItemType Directory -Force -Path (Join-Path $modDir "web") | Out-Null

Copy-Item $dll     -Destination $plugins.FullName -Force
Copy-Item $license -Destination $modDir.FullName  -Force
Copy-Item "$web\*" -Destination (Join-Path $modDir "web") -Recurse -Force

# No ini. The plugin writes a commented default on first run, and shipping one would overwrite the
# settings of anyone updating over an existing install.

$zip = Join-Path $OutDir "AynDualScreen-FalloutNV.zip"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $stage "NVSE") -DestinationPath $zip -CompressionLevel Optimal

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "Packaged $zip`n"
Expand-Archive $zip -DestinationPath "$zip.check" -Force
Get-ChildItem "$zip.check" -Recurse -File |
    Select-Object @{n = 'in the archive'; e = { $_.FullName.Replace("$zip.check\", '') } }, Length |
    Sort-Object 'in the archive' | Format-Table -AutoSize
Remove-Item "$zip.check" -Recurse -Force

<#
.SYNOPSIS
    Builds the mod for every Minecraft version it is known to compile against.

.DESCRIPTION
    The build is target-selectable: `-Pmc`, `-Pforge` and `-Prange` override the defaults in
    gradle.properties, and each target gets its own jar name so the outputs sit side by side in
    build\libs instead of overwriting each other.

    Add a row to $targets to add a version. Anything that fails is reported and does not stop the rest.

.NOTES
    Newer Minecraft is NOT currently buildable from this project, and it is worth knowing why before
    spending an evening on it: ForgeGradle 6 cannot set up MCP for 1.21.11, failing during the "rename"
    step with

        java.util.zip.ZipException: duplicate entry: mcp/client/Start.class

    That is the build toolchain, not this mod's source. Targeting current Forge needs a migration to a
    newer ForgeGradle (or to NeoForge's ModDevGradle), which is a project change rather than a flag.
    The Java in src\main is ordinary client API use and has no obvious reason not to port; it simply
    has not been compiled against a newer version yet, so no claim is made that it does.
#>

param([switch]$Clean)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# mc, forge, and the range the jar declares it supports
$targets = @(
    @{ mc = '1.21.1'; forge = '52.1.0'; range = '[1.21,1.22)' }
)

if ($Clean) { & .\gradlew.bat clean --console=plain -q --no-daemon }

$made = @()
foreach ($target in $targets) {
    Write-Host ("building for Minecraft {0} (Forge {1})..." -f $target.mc, $target.forge)

    & .\gradlew.bat build --console=plain -q --no-daemon `
        "-Pmc=$($target.mc)" "-Pforge=$($target.forge)" "-Prange=$($target.range)"

    if ($LASTEXITCODE -ne 0) {
        Write-Warning ("  {0}: build FAILED" -f $target.mc)
        $global:LASTEXITCODE = 0
        continue
    }
    $made += $target.mc
}

Write-Host ""
if ($made.Count -eq 0) {
    Write-Host "Nothing built."
} else {
    Write-Host ("Built for: " + ($made -join ', '))
    Get-ChildItem 'build\libs\*.jar' | Where-Object { $_.Name -notmatch '-(sources|dev)\.jar$' } |
        ForEach-Object { Write-Host ("  {0}  ({1} KB)" -f $_.Name, [math]::Round($_.Length / 1KB)) }
}

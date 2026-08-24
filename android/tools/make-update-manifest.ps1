<#
.SYNOPSIS
    Writes the AynDualScreen-App.json that the in-app updater reads.

.DESCRIPTION
    The app can work out what version a release contains by reading the release title, and it will
    keep doing that for the six releases published before this file existed. Guessing from prose is
    not something to rely on forever, though: it breaks the first time somebody writes a title
    slightly differently, and the failure is silent -- the app simply stops offering updates.

    So every release from 0.15.0 onward should carry this manifest beside the APK. It states the
    version, the versionCode Android actually compares, the file to download, and the SHA-256 of
    that file, all read out of the built APK rather than typed.

    Upload the result as a release asset named AynDualScreen-App.json.

.PARAMETER Apk
    The built APK, usually android/app/build/outputs/apk/release/app-release.apk.

.PARAMETER Out
    Where to write the manifest. Defaults to AynDualScreen-App.json beside the APK.

.PARAMETER Channel
    stable (default) or beta. A beta manifest is only offered to devices set to the beta channel.

.PARAMETER Notes
    Optional text shown in the app instead of the release body. Leave it out and the app shows the
    release notes, which is usually what you want.

.EXAMPLE
    .\make-update-manifest.ps1 -Apk ..\app\build\outputs\apk\release\app-release.apk

.EXAMPLE
    .\make-update-manifest.ps1 -Apk .\app-release.apk -Channel beta -Out .\AynDualScreen-App.json
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Out,
    [ValidateSet('stable', 'beta')][string]$Channel = 'stable',
    [string]$Notes
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Apk)) { throw "No APK at $Apk" }
$apkFile = Get-Item $Apk

if (-not $Out) { $Out = Join-Path $apkFile.DirectoryName 'AynDualScreen-App.json' }

# ---- find aapt2, which is what can read a version out of a built APK ----------------------------
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$aapt = Get-ChildItem -Path (Join-Path $sdk 'build-tools') -Filter 'aapt2.exe' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1

if (-not $aapt) {
    throw "Could not find aapt2 under $sdk. Set ANDROID_HOME to your SDK, or install build-tools."
}

$badging = & $aapt.FullName dump badging $apkFile.FullName
$package = $badging | Select-String -Pattern "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'"

if (-not $package) { throw "aapt2 could not read a package line out of $($apkFile.Name)" }

$packageName = $package.Matches[0].Groups[1].Value
$versionCode = [int]$package.Matches[0].Groups[2].Value
$versionName = $package.Matches[0].Groups[3].Value

$sdkLine = $badging | Select-String -Pattern "^sdkVersion:'(\d+)'"
$minSdk = if ($sdkLine) { [int]$sdkLine.Matches[0].Groups[1].Value } else { 26 }

if ($packageName -ne 'com.abacus.dualscreen') {
    throw "That APK is $packageName, not com.abacus.dualscreen."
}

# ---- the manifest -------------------------------------------------------------------------------
$sha = (Get-FileHash -Path $apkFile.FullName -Algorithm SHA256).Hash.ToLower()

$manifest = [ordered]@{
    schema      = 1
    channel     = $Channel
    versionName = $versionName
    versionCode = $versionCode
    apk         = 'AynDualScreen-App.apk'
    sha256      = $sha
    size        = $apkFile.Length
    minSdk      = $minSdk
}

if ($Notes) { $manifest.notes = $Notes }

$json = $manifest | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText($Out, $json + "`n", (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Wrote $Out"
Write-Host "  version   $versionName (versionCode $versionCode)"
Write-Host "  channel   $Channel"
Write-Host "  size      $([math]::Round($apkFile.Length / 1MB, 2)) MB"
Write-Host "  sha256    $sha"
Write-Host ""
Write-Host "Upload it with the APK, under exactly the name AynDualScreen-App.json:"
Write-Host "  gh release upload <tag> `"$Out`" `"$($apkFile.FullName)`""

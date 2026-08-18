<#
.SYNOPSIS
    Draws the background art for the built-in console skins.

.DESCRIPTION
    The skins were all palette and no texture, which is why they looked like each other: a 3DS home
    menu is not "light grey", it is a field of rounded empty slots, and a PSP is not "dark blue", it
    is a wave. Colour alone cannot carry that.

    So each skin gets a real background, drawn here rather than downloaded — nothing in this
    repository ships art it does not own, and a script that regenerates them is worth more than a
    folder of PNGs nobody can edit. Change a number, run it again, and the app picks it up.

    Output goes to app/src/main/assets/themes/<id>.png and is loaded by ThemeStore.

    Deliberately no photographs and no console logos: this is geometry in the spirit of each
    machine's menu, not a copy of anyone's assets.

.EXAMPLE
    pwsh -File tools/make-theme-art.ps1
#>

[CmdletBinding()]
param(
    # Resolved from this script's own location. $PSScriptRoot is empty when the file is invoked
    # through some hosts, which silently wrote the art to the drive root the first time this ran.
    [string] $OutputDir = (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "../app/src/main/assets/themes"),

    # Portrait-ish and modest: these are stretched over a panel, and a 4K wallpaper in an APK is
    # megabytes for something nobody will look at closely.
    [int] $Width = 720,
    [int] $Height = 1280
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

New-Item -ItemType Directory -Force $OutputDir | Out-Null

function New-Canvas {
    param([int] $W, [int] $H)
    $bitmap = New-Object System.Drawing.Bitmap $W, $H
    $g = [System.Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    return @{ Bitmap = $bitmap; Graphics = $g }
}

function Set-Gradient {
    param($G, [int] $W, [int] $H, [string] $From, [string] $To, [switch] $Horizontal)

    $rect = New-Object System.Drawing.Rectangle 0, 0, $W, $H
    $angle = if ($Horizontal) { 0 } else { 90 }
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.ColorTranslator]::FromHtml($From),
        [System.Drawing.ColorTranslator]::FromHtml($To),
        [float] $angle)
    $G.FillRectangle($brush, $rect)
    $brush.Dispose()
}

function Add-RoundedSlots {
    <#
        The grid of empty app slots. This is the single most recognisable thing about a 3DS, Wii U
        or Wii home screen, and it is why those three stopped looking identical the moment it was
        added.
    #>
    param($G, [int] $W, [int] $H, [int] $Columns, [string] $Color, [int] $Alpha = 255, [int] $Radius = 18)

    $margin = [int]($W * 0.045)
    $gap = [int]($W * 0.028)
    $size = [int](($W - ($margin * 2) - ($gap * ($Columns - 1))) / $Columns)
    $rows = [int][math]::Ceiling(($H - $margin) / ($size + $gap))

    $base = [System.Drawing.ColorTranslator]::FromHtml($Color)
    $tint = [System.Drawing.Color]::FromArgb($Alpha, $base.R, $base.G, $base.B)
    $brush = New-Object System.Drawing.SolidBrush $tint

    for ($row = 0; $row -lt $rows; $row++) {
        for ($col = 0; $col -lt $Columns; $col++) {
            $x = $margin + $col * ($size + $gap)
            $y = $margin + $row * ($size + $gap)

            $path = New-Object System.Drawing.Drawing2D.GraphicsPath
            $d = $Radius * 2
            $path.AddArc($x, $y, $d, $d, 180, 90)
            $path.AddArc($x + $size - $d, $y, $d, $d, 270, 90)
            $path.AddArc($x + $size - $d, $y + $size - $d, $d, $d, 0, 90)
            $path.AddArc($x, $y + $size - $d, $d, $d, 90, 90)
            $path.CloseFigure()

            $G.FillPath($brush, $path)
            $path.Dispose()
        }
    }

    $brush.Dispose()
}

function Add-Waves {
    <# The XMB's ribbon. Sine bands rather than a copied texture. #>
    param($G, [int] $W, [int] $H, [string] $Color, [int] $Alpha = 40, [int] $Bands = 5)

    $base = [System.Drawing.ColorTranslator]::FromHtml($Color)

    for ($band = 0; $band -lt $Bands; $band++) {
        $tint = [System.Drawing.Color]::FromArgb([int]($Alpha * (1 - $band / [double]$Bands)), $base.R, $base.G, $base.B)
        $pen = New-Object System.Drawing.Pen $tint, ([float](($Bands - $band) * 6))

        $points = New-Object System.Collections.Generic.List[System.Drawing.PointF]
        $amplitude = $H * 0.05
        $offset = $H * (0.30 + $band * 0.06)

        for ($x = 0; $x -le $W; $x += 8) {
            $y = $offset + [math]::Sin(($x / [double]$W) * 6.28 + $band) * $amplitude
            $points.Add((New-Object System.Drawing.PointF([float]$x, [float]$y)))
        }

        $G.DrawLines($pen, $points.ToArray())
        $pen.Dispose()
    }
}

function Add-Bubbles {
    <# The Vita's LiveArea: soft translucent circles over the blue. #>
    param($G, [int] $W, [int] $H, [string] $Color, [int] $Alpha = 26)

    $base = [System.Drawing.ColorTranslator]::FromHtml($Color)
    $tint = [System.Drawing.Color]::FromArgb($Alpha, $base.R, $base.G, $base.B)
    $brush = New-Object System.Drawing.SolidBrush $tint

    $random = New-Object System.Random 20260818     # fixed seed: the art must not change per run
    for ($i = 0; $i -lt 26; $i++) {
        $size = $random.Next([int]($W * 0.10), [int]($W * 0.34))
        $x = $random.Next(-40, $W)
        $y = $random.Next(-40, $H)
        $G.FillEllipse($brush, $x, $y, $size, $size)
    }

    $brush.Dispose()
}

function Add-Vignette {
    param($G, [int] $W, [int] $H, [int] $Alpha = 90)

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(-$W * 0.4, -$H * 0.25, $W * 1.8, $H * 1.5)

    $brush = New-Object System.Drawing.Drawing2D.PathGradientBrush $path
    $brush.CenterColor = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
    $brush.SurroundColors = @([System.Drawing.Color]::FromArgb($Alpha, 0, 0, 0))
    $G.FillRectangle($brush, 0, 0, $W, $H)

    $brush.Dispose()
    $path.Dispose()
}

function Save-Art {
    param($Canvas, [string] $Name)
    $path = Join-Path $OutputDir "$Name.png"
    $Canvas.Graphics.Dispose()
    $Canvas.Bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $Canvas.Bitmap.Dispose()
    "{0,-10} {1,6:N0} KB" -f $Name, ((Get-Item $path).Length / 1KB)
}

Write-Host "Drawing console backgrounds into $OutputDir" -ForegroundColor Cyan

# ── 3DS: pale field, rounded empty slots, four across ────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#F7F7F7" "#E9E9E9"
Add-RoundedSlots $c.Graphics $Width $Height 4 "#DCDCDC" 255 18
Save-Art $c "3ds"

# ── Wii U: lighter, warmer, five across, softer slots ────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#FFFFFF" "#F1F1F1"
Add-RoundedSlots $c.Graphics $Width $Height 5 "#E6E6E6" 255 16
Save-Art $c "wiiu"

# ── Wii: cooler white, wider channel slots ───────────────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#FFFFFF" "#E8EFF4"
Add-RoundedSlots $c.Graphics $Width $Height 4 "#DCE6ED" 255 12
Save-Art $c "wii"

# ── Switch: charcoal with a vignette, no slots ───────────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#333333" "#1B1B1B"
Add-Vignette $c.Graphics $Width $Height 110
Save-Art $c "switch"

# ── PSP: XMB ribbon over deep blue ───────────────────────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#0B1730" "#04070F"
Add-Waves $c.Graphics $Width $Height "#5FA8FF" 55 6
Save-Art $c "psp"

# ── Vita: blue wash with LiveArea bubbles ────────────────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#1F6FB8" "#9AD0EE"
Add-Bubbles $c.Graphics $Width $Height "#FFFFFF" 30
Save-Art $c "vita"

# ── PlayStation: dark blue, faint wave, vignette ─────────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#161A28" "#2A3048"
Add-Waves $c.Graphics $Width $Height "#8FA0D0" 30 4
Add-Vignette $c.Graphics $Width $Height 70
Save-Art $c "ps1"

# ── DS Lite: silver-white, small slots, no gradient drama ────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#F4F7FA" "#E2E9EF"
Add-RoundedSlots $c.Graphics $Width $Height 3 "#D7E0E8" 255 14
Save-Art $c "dslite"

Write-Host "Done." -ForegroundColor Green

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
    [string] $OutputDir = "",

    # Portrait-ish and modest: these are stretched over a panel, and a 4K wallpaper in an APK is
    # megabytes for something nobody will look at closely.
    [int] $Width = 720,
    [int] $Height = 1280
)

# Resolve the output folder here rather than in the parameter default: $PSScriptRoot and
# $MyInvocation are both unreliable in a param() block depending on how the file is invoked, and
# the first version of this silently wrote the art to the drive root.
if (-not $OutputDir) {
    $here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Definition }
    $OutputDir = Join-Path $here "../app/src/main/assets/themes"
}

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
    param($G, [int] $W, [int] $H, [string] $Color, [int] $Alpha = 26, [int] $Count = 26)

    $base = [System.Drawing.ColorTranslator]::FromHtml($Color)
    $tint = [System.Drawing.Color]::FromArgb($Alpha, $base.R, $base.G, $base.B)
    $brush = New-Object System.Drawing.SolidBrush $tint

    $random = New-Object System.Random 20260818     # fixed seed: the art must not change per run
    for ($i = 0; $i -lt $Count; $i++) {
        $size = $random.Next([int]($W * 0.18), [int]($W * 0.46))
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

<#
    A background sits BEHIND things.

    The first pass forgot that. It baked a slot grid into the wallpaper while the app was also
    drawing slot fillers into the grid itself — two grids at different pitches, on top of each
    other, that can never line up because one is stretched to the screen and the other is laid out
    by the tiles. And the Vita's bubbles were big and opaque enough to fight the labels sitting on
    them.

    So: no structure that the layout also draws, and everything at low contrast. The slots come
    from ConsoleSkin.slotFillers, which align with real tiles because they ARE tiles. What is left
    here is atmosphere — a wash, a faint ribbon, a soft vignette.
#>

# ── 3DS: pale, faintly warm wash. Slots come from the layout, not from here ──
$c = New-Canvas $Width $Height
# No vignette on the light skins: a soft radial over a near-white field shows as visible
# concentric banding at 8-bit colour, which looks like a rendering fault rather than depth.
Set-Gradient $c.Graphics $Width $Height "#FAFAFA" "#EBEBEB"
Save-Art $c "3ds"

# ── Wii U: brighter and cooler than the 3DS, same restraint ─────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#FFFFFF" "#EDF1F4"
Save-Art $c "wiiu"

# ── Wii: white with the faintest blue toward the bottom ─────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#FFFFFF" "#E7EEF4"
Save-Art $c "wii"

# ── Switch: charcoal, strong vignette, nothing else ─────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#3A3A3A" "#161616"
Add-Vignette $c.Graphics $Width $Height 120
Save-Art $c "switch"

# ── PSP: the XMB ribbon, but as atmosphere rather than as a subject ─────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#0C1A38" "#05080F"
Add-Waves $c.Graphics $Width $Height "#4E8FE0" 18 5
Add-Vignette $c.Graphics $Width $Height 60
Save-Art $c "psp"

# ── Vita: the blue wash. A few soft bubbles, not a bubble bath ──────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#2A7CC0" "#A6D6EF"
Add-Bubbles $c.Graphics $Width $Height "#FFFFFF" 10 9
Save-Art $c "vita"

# ── PlayStation: dark blue with one slow wave ──────────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#191D2C" "#2C3350"
Add-Waves $c.Graphics $Width $Height "#8FA0D0" 12 3
Add-Vignette $c.Graphics $Width $Height 80
Save-Art $c "ps1"

# ── DS Lite: silver, almost flat ───────────────────────────────────────────
$c = New-Canvas $Width $Height
Set-Gradient $c.Graphics $Width $Height "#F6F8FA" "#E4EAF0"
Save-Art $c "dslite"

Write-Host "Done." -ForegroundColor Green

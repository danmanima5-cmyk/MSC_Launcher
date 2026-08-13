param(
    [string]$Source = "$PSScriptRoot\..\src\main\resources\Resources\launcher-icon.png",
    [string]$OutputDirectory = "$PSScriptRoot\..\metro-appx\images"
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$sourcePath = [IO.Path]::GetFullPath($Source)
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($outputPath) | Out-Null

$sourceImage = [Drawing.Image]::FromFile($sourcePath)

function New-TileImage {
    param(
        [string]$Name,
        [int]$Width,
        [int]$Height,
        [int]$IconSize
    )

    $bitmap = New-Object Drawing.Bitmap $Width, $Height
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([Drawing.Color]::FromArgb(106, 61, 154))
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
        $x = [int](($Width - $IconSize) / 2)
        $y = [int](($Height - $IconSize) / 2)
        $graphics.DrawImage($sourceImage, $x, $y, $IconSize, $IconSize)
        $bitmap.Save((Join-Path $outputPath $Name), [Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

try {
    New-TileImage 'logo-30.png' 30 30 26
    New-TileImage 'store-logo.png' 50 50 44
    New-TileImage 'logo-70.png' 70 70 60
    New-TileImage 'logo-150.png' 150 150 126
    New-TileImage 'logo-310x150.png' 310 150 126
    New-TileImage 'splash-620x300.png' 620 300 220
}
finally {
    $sourceImage.Dispose()
}

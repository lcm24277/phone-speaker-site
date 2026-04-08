Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$iconDir = Join-Path $root "build\icons"
New-Item -ItemType Directory -Force -Path $iconDir | Out-Null

$pngPath = Join-Path $iconDir "app.png"
$icoPath = Join-Path $iconDir "app.ico"

$size = 256
$bitmap = New-Object System.Drawing.Bitmap $size, $size
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::Transparent)

$outerRect = New-Object System.Drawing.RectangleF 8, 8, 240, 240
$outerPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$radius = 40
$diameter = $radius * 2
$outerPath.AddArc($outerRect.X, $outerRect.Y, $diameter, $diameter, 180, 90)
$outerPath.AddArc($outerRect.Right - $diameter, $outerRect.Y, $diameter, $diameter, 270, 90)
$outerPath.AddArc($outerRect.Right - $diameter, $outerRect.Bottom - $diameter, $diameter, $diameter, 0, 90)
$outerPath.AddArc($outerRect.X, $outerRect.Bottom - $diameter, $diameter, $diameter, 90, 90)
$outerPath.CloseFigure()

$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
  (New-Object System.Drawing.Point 0, 0),
  (New-Object System.Drawing.Point 0, $size),
  ([System.Drawing.Color]::FromArgb(255, 47, 186, 255)),
  ([System.Drawing.Color]::FromArgb(255, 15, 24, 164))
)
$graphics.FillPath($bgBrush, $outerPath)

$shineBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
  (New-Object System.Drawing.Point 0, 0),
  (New-Object System.Drawing.Point 0, 120),
  ([System.Drawing.Color]::FromArgb(160, 255, 255, 255)),
  ([System.Drawing.Color]::FromArgb(15, 255, 255, 255))
)
$graphics.FillEllipse($shineBrush, 16, 18, 224, 96)

$phonePath = New-Object System.Drawing.Drawing2D.GraphicsPath
$phoneRect = New-Object System.Drawing.RectangleF 58, 34, 140, 192
$phoneRadius = 24
$phoneDiameter = $phoneRadius * 2
$phonePath.AddArc($phoneRect.X, $phoneRect.Y, $phoneDiameter, $phoneDiameter, 180, 90)
$phonePath.AddArc($phoneRect.Right - $phoneDiameter, $phoneRect.Y, $phoneDiameter, $phoneDiameter, 270, 90)
$phonePath.AddArc($phoneRect.Right - $phoneDiameter, $phoneRect.Bottom - $phoneDiameter, $phoneDiameter, $phoneDiameter, 0, 90)
$phonePath.AddArc($phoneRect.X, $phoneRect.Bottom - $phoneDiameter, $phoneDiameter, $phoneDiameter, 90, 90)
$phonePath.CloseFigure()

$phoneBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
  (New-Object System.Drawing.Point 0, 34),
  (New-Object System.Drawing.Point 0, 226),
  ([System.Drawing.Color]::FromArgb(255, 93, 211, 255)),
  ([System.Drawing.Color]::FromArgb(255, 15, 23, 175))
)
$graphics.FillPath($phoneBrush, $phonePath)
$graphics.DrawPath((New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(225, 240, 248, 255), 5)), $phonePath)

$speakerBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
  (New-Object System.Drawing.Point 88, 110),
  (New-Object System.Drawing.Point 140, 182),
  ([System.Drawing.Color]::FromArgb(255, 255, 255, 255)),
  ([System.Drawing.Color]::FromArgb(255, 220, 235, 255))
)
$graphics.FillRectangle($speakerBrush, 82, 114, 20, 50)

$hornPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$hornPath.AddPolygon(@(
  (New-Object System.Drawing.Point 106, 114),
  (New-Object System.Drawing.Point 138, 88),
  (New-Object System.Drawing.Point 138, 190),
  (New-Object System.Drawing.Point 106, 164)
))
$graphics.FillPath($speakerBrush, $hornPath)

foreach ($arc in @(
  @{X=146;Y=114;W=30;H=48},
  @{X=156;Y=100;W=44;H=78},
  @{X=166;Y=90;W=56;H=96}
)) {
  $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(245, 255, 255, 255), 6)
  $graphics.DrawArc($pen, $arc.X, $arc.Y, $arc.W, $arc.H, -52, 104)
  $pen.Dispose()
}

$borderPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(200, 17, 100, 214), 6)
$graphics.DrawPath($borderPen, $outerPath)

$bitmap.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png)

$memory = New-Object System.IO.MemoryStream
$bitmap.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
$pngBytes = $memory.ToArray()
$file = [System.IO.File]::Open($icoPath, [System.IO.FileMode]::Create)
$writer = New-Object System.IO.BinaryWriter($file)
$writer.Write([UInt16]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]1)
$writer.Write([byte]0)
$writer.Write([byte]0)
$writer.Write([byte]0)
$writer.Write([byte]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]32)
$writer.Write([UInt32]$pngBytes.Length)
$writer.Write([UInt32]22)
$writer.Write($pngBytes)
$writer.Flush()
$writer.Dispose()
$file.Dispose()
$memory.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

Write-Output \"Generated icon assets:`n$pngPath`n$icoPath\"

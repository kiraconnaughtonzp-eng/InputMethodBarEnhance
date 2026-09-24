#Requires -Version 5.1
<#
    图标预览工具（开发用，不影响 APK）。

    把 ime/BarIcons.kt 里的图标几何在桌面渲染成 PNG，用来"先看效果再改代码"。
    用法：
        powershell -ExecutionPolicy Bypass -File tools\icon-preview.ps1
    产物：tools\icon-preview.png（深色/浅色各一行）

    注意：这里只是几何预览（GDI+），与 Android Canvas 的路径语义一致：
    24x24 视口、线宽 2、圆头圆角；角度 0=3点钟方向、顺时针为正。
#>
Add-Type -AssemblyName System.Drawing

$script:Scale = 3.0
$script:Stroke = 2.0

function New-Pen([System.Drawing.Color]$color) {
    $pen = New-Object System.Drawing.Pen $color, ($script:Stroke * $script:Scale)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    return $pen
}

function L([System.Drawing.Graphics]$g, [System.Drawing.Pen]$p, $x1, $y1, $x2, $y2) {
    $g.DrawLine($p, $x1 * $script:Scale, $y1 * $script:Scale, $x2 * $script:Scale, $y2 * $script:Scale)
}

function RR([System.Drawing.Graphics]$g, [System.Drawing.Pen]$p, $x, $y, $w, $h, $r, [switch]$Dashed) {
    if ($Dashed) {
        $old = $p.DashStyle
        $p.DashStyle = [System.Drawing.Drawing2D.DashStyle]::Dot
        $p.DashPattern = @(1.6, 1.6)
    }
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($x * $script:Scale, $y * $script:Scale, $r * 2 * $script:Scale, $r * 2 * $script:Scale, 180, 90)
    $path.AddArc(($x + $w - $r * 2) * $script:Scale, $y * $script:Scale, $r * 2 * $script:Scale, $r * 2 * $script:Scale, 270, 90)
    $path.AddArc(($x + $w - $r * 2) * $script:Scale, ($y + $h - $r * 2) * $script:Scale, $r * 2 * $script:Scale, $r * 2 * $script:Scale, 0, 90)
    $path.AddArc($x * $script:Scale, ($y + $h - $r * 2) * $script:Scale, $r * 2 * $script:Scale, $r * 2 * $script:Scale, 90, 90)
    $path.CloseFigure()
    $g.DrawPath($p, $path)
    $path.Dispose()
    if ($Dashed) { $p.DashStyle = $old }
}

function C([System.Drawing.Graphics]$g, [System.Drawing.Pen]$p, $cx, $cy, $r) {
    $g.DrawEllipse($p, ($cx - $r) * $script:Scale, ($cy - $r) * $script:Scale, $r * 2 * $script:Scale, $r * 2 * $script:Scale)
}

function A([System.Drawing.Graphics]$g, [System.Drawing.Pen]$p, $cx, $cy, $r, $start, $sweep) {
    $g.DrawArc($p, ($cx - $r) * $script:Scale, ($cy - $r) * $script:Scale, $r * 2 * $script:Scale, $r * 2 * $script:Scale, $start, $sweep)
}

function P([System.Drawing.Graphics]$g, [System.Drawing.Pen]$p, $pts) {
    $arr = @()
    for ($i = 0; $i -lt $pts.Count; $i += 2) {
        $arr += New-Object System.Drawing.PointF ($pts[$i] * $script:Scale), ($pts[$i + 1] * $script:Scale)
    }
    $g.DrawLines($p, $arr)
}

function Draw-Icon([System.Drawing.Graphics]$g, [System.Drawing.Pen]$p, [string]$name) {
    switch ($name) {
        'select_all' {
            RR $g $p 3.5 3.5 17 17 3 -Dashed
            P $g $p @(8.6, 12.2, 11.0, 14.6, 15.8, 9.2)
        }
        'copy' {
            RR $g $p 9 9 11.5 11.5 2.5
            $path = New-Object System.Drawing.Drawing2D.GraphicsPath
            $path.AddLine(15.3 * $script:Scale, 5 * $script:Scale, 7 * $script:Scale, 5 * $script:Scale)
            $path.AddArc(4.5 * $script:Scale, 5 * $script:Scale, 5 * $script:Scale, 5 * $script:Scale, 180, 90)
            $path.AddLine(4.5 * $script:Scale, 7.5 * $script:Scale, 4.5 * $script:Scale, 15.3 * $script:Scale)
            $g.DrawPath($p, $path); $path.Dispose()
        }
        'cut' {
            C $g $p 6.8 18.6 2.4
            C $g $p 17.2 18.6 2.4
            L $g $p 8.4 16.9 17.6 4.6
            L $g $p 15.6 16.9 6.4 4.6
        }
        'paste' {
            RR $g $p 5 5 14 15.5 2
            RR $g $p 9 3 6 4 1.5
            L $g $p 12 9.8 12 15.2
            P $g $p @(9.7, 12.9, 12, 15.2, 14.3, 12.9)
        }
        'clipboard' {
            RR $g $p 5 5 14 15.5 2
            RR $g $p 9 3 6 4 1.5
            L $g $p 8.6 11.6 15.4 11.6
            L $g $p 8.6 14.6 15.4 14.6
            L $g $p 8.6 17.6 12.8 17.6
        }
        'clear' {
            L $g $p 4.5 6.8 19.5 6.8
            L $g $p 9.5 4 14.5 4
            RR $g $p 7 7 10 13.5 2.5
            L $g $p 10.8 10.6 10.8 16.8
            L $g $p 13.2 10.6 13.2 16.8
        }
        'cursor_left' { P $g $p @(14.5, 5.5, 8, 12, 14.5, 18.5) }
        'cursor_right' { P $g $p @(9.5, 5.5, 16, 12, 9.5, 18.5) }
        'line_start' {
            L $g $p 4.8 5.5 4.8 18.5
            L $g $p 17.2 12 7.6 12
            P $g $p @(10.6, 8.8, 7.6, 12, 10.6, 15.2)
        }
        'line_end' {
            L $g $p 19.2 5.5 19.2 18.5
            L $g $p 6.8 12 16.4 12
            P $g $p @(13.4, 8.8, 16.4, 12, 13.4, 15.2)
        }
        'undo' {
            A $g $p 12 12.6 6 180 180
            P $g $p @(9.2, 9.4, 6.0, 12.6, 9.2, 15.8)
        }
        'redo' {
            A $g $p 12 12.6 6 180 180
            P $g $p @(14.8, 9.4, 18.0, 12.6, 14.8, 15.8)
        }
        'hide_keyboard' {
            P $g $p @(6, 9.5, 12, 15.5, 18, 9.5)
            L $g $p 6 19 18 19
        }
        default { }
    }
}

$icons = @('select_all', 'copy', 'cut', 'paste', 'clipboard', 'clear', 'cursor_left', 'cursor_right',
    'line_start', 'line_end', 'undo', 'redo', 'hide_keyboard')

$labels = @{
    'select_all' = '全选'; 'copy' = '复制'; 'cut' = '剪切'; 'paste' = '粘贴'; 'clipboard' = '剪贴板'
    'clear' = '清空'; 'cursor_left' = '左移'; 'cursor_right' = '右移'; 'line_start' = '行首'
    'line_end' = '行尾'; 'undo' = '撤销'; 'redo' = '重做'; 'hide_keyboard' = '收起'
}

$cell = 24 * $script:Scale
$gap = 26
$labelH = 30
$cols = 7
$rows = [Math]::Ceiling($icons.Count / $cols)
$width = [int]($cols * ($cell + $gap) + $gap)
$rowH = [int]($cell + $gap + $labelH)
$height = [int]($rows * $rowH + $gap + 34)

$bmp = New-Object System.Drawing.Bitmap $width, $height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::FromArgb(28, 31, 36))

$font = New-Object System.Drawing.Font('Microsoft YaHei', 9)
$brushLight = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(233, 234, 236))
$brushDim = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(154, 160, 166))
$g.DrawString('深色主题 (键盘底栏)', (New-Object System.Drawing.Font('Microsoft YaHei', 10, [System.Drawing.FontStyle]::Bold)), $brushLight, $gap, 8)

$pen = New-Pen ([System.Drawing.Color]::FromArgb(233, 234, 236))
for ($i = 0; $i -lt $icons.Count; $i++) {
    $col = $i % $cols
    $row = [Math]::Floor($i / $cols)
    $x = $gap + $col * ($cell + $gap)
    $y = 34 + $row * $rowH
    $g.ResetTransform()
    $g.TranslateTransform($x, $y)
    Draw-Icon $g $pen $icons[$i]
    $g.ResetTransform()
    $g.DrawString($labels[$icons[$i]], $font, $brushDim, $x, ($y + $cell + 4))
}
$pen.Dispose()
$g.Dispose()

$out = Join-Path $PSScriptRoot 'icon-preview.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "已生成预览图：$out" -ForegroundColor Green

<#
  The splash the CAT-Java shortcut shows while CAT starts: the expanded sidebar's Commander-dots CAT logo on
  the sidebar's colour, and nothing else. run.cmd starts it, on its own console, when it is given --splash
  (desktop.Shortcuts puts that on CAT-Java.lnk), before it checks the build: the logo shows within a second
  of the click, through a build, the Java 17 download and the JVM's start. There is no need to run it by hand.

  It closes itself on the first of:
    - ~\.cat\java\splash.ready written after this splash started: CAT-Java's page has opened (desktop.Splash;
      WebServe writes it on the first page's poll, or 60 s after opening the window), or bootstrap.ps1 is
      about to show a failure (Show-Notice);
    - the cmd.exe running run.cmd has ended (CAT could not start, or a second click handed over to the
      CAT already running);
    - a click on it;
    - $MaxMinutes (a first build, every library downloaded, fits).

  It opens in front and then lets go of the top, so a window that opens later (the sign-in window, CAT's
  own) goes over it. It takes no focus and has no taskbar button. Any error ends it quietly: the splash
  never stops CAT from starting.

  Windows PowerShell 5.1 and WPF: no ternaries, no null-coalescing.
#>
param(
    [string]$Ready = (Join-Path $env:USERPROFILE '.cat\java\splash.ready'),
    [int]$MaxMinutes = 10
)

$ErrorActionPreference = 'Stop'

# The sidebar's colours (cat.css: --cat-side, --cat-side-hover) and the logo it shows when expanded.
$Background = '#101E26'
$Edge = '#25313E'
$Logo = Join-Path $PSScriptRoot 'src\main\resources\public\icons\cat-commander-logo.png'
$LogoWidth = 560   # the logo is 2688 x 548

try {
    if (-not (Test-Path -LiteralPath $Logo -PathType Leaf)) { exit 0 }
    Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase

    $script:started = (Get-Process -Id $PID).StartTime.ToUniversalTime()
    $script:deadline = [DateTime]::UtcNow.AddMinutes($MaxMinutes)
    $script:parent = $null
    $script:parentChecked = $false

    $bitmap = New-Object Windows.Media.Imaging.BitmapImage
    $bitmap.BeginInit()
    $bitmap.UriSource = New-Object Uri($Logo)
    $bitmap.CacheOption = [Windows.Media.Imaging.BitmapCacheOption]::OnLoad
    $bitmap.EndInit()
    $bitmap.Freeze()

    $image = New-Object Windows.Controls.Image
    $image.Source = $bitmap
    $image.Width = $LogoWidth
    $image.Height = [Math]::Round($LogoWidth * $bitmap.PixelHeight / $bitmap.PixelWidth, 1)
    $image.Stretch = [Windows.Media.Stretch]::Uniform
    [Windows.Media.RenderOptions]::SetBitmapScalingMode($image, [Windows.Media.BitmapScalingMode]::HighQuality)

    $converter = New-Object Windows.Media.BrushConverter
    $border = New-Object Windows.Controls.Border
    $border.Background = $converter.ConvertFromString($Background)
    $border.BorderBrush = $converter.ConvertFromString($Edge)
    $border.BorderThickness = New-Object Windows.Thickness(1)
    $border.CornerRadius = New-Object Windows.CornerRadius(8)
    $border.Padding = New-Object Windows.Thickness(44, 40, 44, 40)
    $border.Child = $image

    $window = New-Object Windows.Window
    $window.Title = 'CAT-Java'
    $window.WindowStyle = [Windows.WindowStyle]::None
    $window.ResizeMode = [Windows.ResizeMode]::NoResize
    $window.AllowsTransparency = $true
    $window.Background = [Windows.Media.Brushes]::Transparent
    $window.ShowInTaskbar = $false
    $window.ShowActivated = $false
    $window.Topmost = $true
    $window.SizeToContent = [Windows.SizeToContent]::WidthAndHeight
    $window.WindowStartupLocation = [Windows.WindowStartupLocation]::CenterScreen
    $window.Content = $border
    $script:window = $window

    # In front once it has drawn, then not pinned there.
    $window.Add_ContentRendered({ $script:window.Topmost = $false })
    $window.Add_MouseLeftButtonDown({ $script:window.Close() })

    # The cmd.exe that started this splash: looked up once the logo shows, so the lookup adds no wait.
    $findParent = {
        $script:parentChecked = $true
        try {
            $parentId = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId
            $process = [Diagnostics.Process]::GetProcessById([int]$parentId)
            # A process started after this splash only reuses the number of the one that started it.
            if ($process.StartTime.ToUniversalTime() -gt $script:started) { return $false }
            $script:parent = $process
            return $true
        } catch {
            return $false
        }
    }

    $done = {
        if ([DateTime]::UtcNow -gt $script:deadline) { return $true }
        if (Test-Path -LiteralPath $Ready -PathType Leaf) {
            if ((Get-Item -LiteralPath $Ready).LastWriteTimeUtc -ge $script:started) { return $true }
        }
        if (-not $script:parentChecked) { return (-not (& $findParent)) }
        if ($script:parent -and $script:parent.HasExited) { return $true }
        return $false
    }

    $timer = New-Object Windows.Threading.DispatcherTimer
    $timer.Interval = [TimeSpan]::FromMilliseconds(250)
    $timer.Add_Tick({
        try {
            if (& $done) { $script:timer.Stop(); $script:window.Close() }
        } catch {
            $script:timer.Stop()
            $script:window.Close()
        }
    })
    $script:timer = $timer
    $window.Add_ContentRendered({ $script:timer.Start() })

    [void]$window.ShowDialog()
} catch {
    exit 0
}
exit 0

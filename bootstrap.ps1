<#
  CAT-Java's bootstrap for a controller that may have nothing on it. build.cmd and run.cmd call it;
  there is no need to run it by hand.

    bootstrap.ps1 -Java [-ToolsRoot C:\CICD\Tools]
        Stages Java 17 v1.2 from Artifactory into <tools>\Java17 unless jdk-17.0.5+8\bin\java.exe is
        already there. Exit 0 when it is there, 1 when it could not be staged, 3 when it could not and
        a window has already told the operator (or they cancelled the sign-in window).
    bootstrap.ps1 -NeedsBuild
        Exit 10 (and says why) when target\cat-java.jar is missing or older than pom.xml, .mvn\ or
        anything under src\main\; exit 0 when the jar is current.
    bootstrap.ps1 -MarkBuilt
        Stamps target\cat-java.jar as built now, after a package build that succeeded, so a source
        file that changed without changing the jar does not rebuild on every start.
    bootstrap.ps1 -FailedTests
        Lists the test classes whose target\surefire-reports\TEST-*.xml record a failure or an error.
    bootstrap.ps1 -Clicked
        Exit 0 when the calling script was double-clicked in Explorer (its window closes when it ends, so
        the script pauses on a failure); 1 from a console, an agent, or the CAT-Java.lnk shortcut's
        headless console, where a pause would wait unseen.
    bootstrap.ps1 -Failed
        run.cmd's failure: exit 0 when double-clicked (run.cmd then pauses); under the headless console,
        a message box saying CAT-Java could not start, then exit 1.

  The Java download is the PowerShell counterpart of sims.base.JavaSets.stage for v1.2 (CAT-Java's own
  Java 17; fetch-sims stages v1.1 and the simulators once CAT runs). The login is found as
  build.StoredArtifactoryCredentials finds it: ARTIFACTORY_USER and ARTIFACTORY_PASSWORD, else the first
  saved file that decrypts (~\.cat\java\artifactory.credentials, the Python CAT's
  .secrets\artifactory.credentials, Monarch's .credentials). With none, it asks once (in the console, or in
  a sign-in window under the CAT-Java.lnk shortcut's headless console) and, when Artifactory
  accepts the login, saves it to ~\.cat\java\artifactory.credentials in Monarch's format (a DPAPI blob
  tied to this Windows account), where CAT-Java's own fetch-sims and Setup page read it. It never writes
  the Python CAT's files.

  Windows PowerShell 5.1: no ternaries, no null-coalescing.
#>
param(
    [switch]$Java,
    [switch]$NeedsBuild,
    [switch]$MarkBuilt,
    [switch]$FailedTests,
    [switch]$Clicked,
    [switch]$Failed,
    [string]$ToolsRoot = 'C:\CICD\Tools'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is many times slower with its progress bar
$Repo = $PSScriptRoot

# --- the build's freshness --------------------------------------------------------------------------

function Get-BuildReason {
    $jar = Join-Path $Repo 'target\cat-java.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { return 'target\cat-java.jar is missing' }
    if (-not (Test-Path -LiteralPath (Join-Path $Repo 'target\lib') -PathType Container)) {
        return 'target\lib is missing'
    }
    $built = (Get-Item -LiteralPath $jar).LastWriteTimeUtc
    $inputs = @(Get-Item -LiteralPath (Join-Path $Repo 'pom.xml'))
    foreach ($dir in @('src\main', '.mvn')) {
        $path = Join-Path $Repo $dir
        if (Test-Path -LiteralPath $path) { $inputs += @(Get-ChildItem -LiteralPath $path -Recurse -File) }
    }
    $newer = $inputs | Where-Object { $_.LastWriteTimeUtc -gt $built } | Select-Object -First 1
    if ($newer) {
        return ($newer.FullName.Substring($Repo.Length).TrimStart('\') + ' changed since the last build')
    }
    return ''
}

# --- how the script was started ---------------------------------------------------------------------

# This process's parent, grandparent, ... (a few levels; a parent that has exited ends the walk).
function Get-Ancestors {
    $chain = @()
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
    for ($i = 0; $i -lt 6 -and $process; $i++) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.ParentProcessId)"
        if ($process) { $chain += $process }
    }
    return $chain
}

# Under the CAT-Java.lnk shortcut's conhost --headless (desktop.Shortcuts) nobody sees the console: a prompt
# or a pause would wait unseen, so the login is asked in a window and a failure is said in one.
$script:Headless = $null
function Test-Headless {
    if ($null -eq $script:Headless) {
        $script:Headless = $false
        foreach ($process in (Get-Ancestors)) {
            if ($process.Name -eq 'conhost.exe' -and "$($process.CommandLine)" -match '--headless') {
                $script:Headless = $true
            }
        }
    }
    return $script:Headless
}

# Double-clicked: the cmd.exe running the script was started by Explorer, so its window closes when it ends.
function Test-Clicked {
    $chain = @(Get-Ancestors)
    for ($i = 0; $i -lt $chain.Count - 1; $i++) {
        if ($chain[$i].Name -eq 'cmd.exe') { return ($chain[$i + 1].Name -eq 'explorer.exe') }
    }
    return $false
}

# --- the Artifactory login --------------------------------------------------------------------------

$ArtifactoryBase = 'https://artifactory.verifone.com/artifactory'   # ArtifactoryClient.DEFAULT_BASE_URL
$JavaRepoPath = 'RMS_Release_CLW/Petro/ToolsInternal/Java17_automation/v1.2/Java17.zip'   # JavaSets.V12
$JavaHolds = 'Java17\Java17'   # the folder inside the zip whose contents become <tools>\Java17
$Jdk = 'jdk-17.0.5+8'          # the JDK build.cmd and run.cmd run on
$CatJavaLogin = Join-Path $env:USERPROFILE '.cat\java\artifactory.credentials'

function ConvertTo-Plain([Security.SecureString]$secure) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# Everything from the first @ dropped, as StoredArtifactoryCredentials.normaliseUsername.
function Get-NormalUser([string]$user) {
    $user = "$user".Trim()
    $at = $user.IndexOf('@')
    if ($at -ge 0) { $user = $user.Substring(0, $at) }
    return $user.Trim()
}

# One saved file in Monarch's format, or $null when it is missing, malformed or will not decrypt here.
function Read-SavedLogin([string]$file) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return $null }
    try {
        $saved = [IO.File]::ReadAllText($file) | ConvertFrom-Json
        if (-not $saved.Username -or -not $saved.Password) { return $null }
        $password = ConvertTo-Plain (ConvertTo-SecureString -String $saved.Password)
        return @{ User = (Get-NormalUser $saved.Username); Password = $password; From = $file; Save = $false }
    } catch {
        return $null
    }
}

function Get-SavedLogin {
    if ($env:ARTIFACTORY_USER -and $env:ARTIFACTORY_PASSWORD) {
        return @{ User = (Get-NormalUser $env:ARTIFACTORY_USER); Password = $env:ARTIFACTORY_PASSWORD
                  From = 'ARTIFACTORY_USER/ARTIFACTORY_PASSWORD'; Save = $false }
    }
    $python = 'C:\gitrepos\CAT'
    if ($env:CAT_PYTHON_ROOT) { $python = $env:CAT_PYTHON_ROOT }
    $files = @($CatJavaLogin,
               (Join-Path $python '.secrets\artifactory.credentials'),
               'C:\CICD\Scripts\Overarch\nightly-agent\.credentials',
               'C:\CICD\Scripts\nightly-agent\.credentials')
    foreach ($file in $files) {
        $login = Read-SavedLogin $file
        if ($login) { return $login }
    }
    return $null
}

# --- windows, for the shortcut's headless console ----------------------------------------------------

# The sign-in window: its form and the two boxes, not yet shown. $problem (why the last login failed) is
# shown in red above the boxes when given.
function New-LoginDialog([string]$problem) {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $width = 400

    $form = New-Object Windows.Forms.Form
    $form.Text = 'CAT-Java: sign in to Artifactory'
    $form.Font = New-Object Drawing.Font('Segoe UI', 9)
    $form.AutoScaleMode = [Windows.Forms.AutoScaleMode]::Font
    $form.FormBorderStyle = [Windows.Forms.FormBorderStyle]::FixedDialog
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.StartPosition = [Windows.Forms.FormStartPosition]::CenterScreen
    $form.TopMost = $true   # started from a shortcut with no window of its own, it must not open behind others
    $form.AutoSize = $true
    $form.AutoSizeMode = [Windows.Forms.AutoSizeMode]::GrowAndShrink
    $form.Padding = New-Object Windows.Forms.Padding(14, 12, 14, 10)

    $layout = New-Object Windows.Forms.TableLayoutPanel
    $layout.ColumnCount = 1
    $layout.AutoSize = $true
    $layout.AutoSizeMode = [Windows.Forms.AutoSizeMode]::GrowAndShrink
    $form.Controls.Add($layout)

    $addLabel = {
        param([string]$text, [int]$after, $color)
        $label = New-Object Windows.Forms.Label
        $label.Text = $text
        $label.AutoSize = $true
        $label.MaximumSize = New-Object Drawing.Size($width, 0)
        $label.Margin = New-Object Windows.Forms.Padding(0, 0, 0, $after)
        if ($color) { $label.ForeColor = $color }
        $layout.Controls.Add($label)
    }
    $addBox = {
        param([bool]$secret)
        $box = New-Object Windows.Forms.TextBox
        $box.Width = $width
        $box.UseSystemPasswordChar = $secret
        $box.Margin = New-Object Windows.Forms.Padding(0, 2, 0, 10)
        $layout.Controls.Add($box)
        return $box
    }

    & $addLabel ('CAT-Java needs Java 17 before it can open, and downloads it from Artifactory (about 220 MB). ' +
        'Sign in with your Verifone login. It is saved encrypted for this Windows account only, so you are ' +
        'asked once.') 8 $null
    & $addLabel 'CAT-Java opens by itself when it is ready, in a few minutes.' 12 $null
    if ($problem) { & $addLabel $problem 10 ([Drawing.Color]::Firebrick) }
    & $addLabel 'Artifactory user' 0 $null
    $user = & $addBox $false
    & $addLabel 'Password or API key' 0 $null
    $password = & $addBox $true

    $buttons = New-Object Windows.Forms.FlowLayoutPanel
    $buttons.FlowDirection = [Windows.Forms.FlowDirection]::RightToLeft
    $buttons.AutoSize = $true
    $buttons.Dock = [Windows.Forms.DockStyle]::Fill
    $buttons.Margin = New-Object Windows.Forms.Padding(0, 4, 0, 0)
    $cancel = New-Object Windows.Forms.Button
    $cancel.Text = 'Cancel'
    $cancel.DialogResult = [Windows.Forms.DialogResult]::Cancel
    $cancel.AutoSize = $true
    $ok = New-Object Windows.Forms.Button
    $ok.Text = 'Sign in'
    $ok.DialogResult = [Windows.Forms.DialogResult]::OK
    $ok.AutoSize = $true
    $buttons.Controls.Add($cancel)
    $buttons.Controls.Add($ok)
    $layout.Controls.Add($buttons)

    $form.AcceptButton = $ok
    $form.CancelButton = $cancel
    $form.ActiveControl = $user
    $update = { $ok.Enabled = ($user.Text.Trim() -ne '' -and $password.Text -ne '') }.GetNewClosure()
    $user.Add_TextChanged($update)
    $password.Add_TextChanged($update)
    & $update
    $form.Add_Shown({ $this.Activate() })
    return @{ Form = $form; User = $user; Password = $password }
}

# The login from the sign-in window, or $null when it is cancelled or cannot be shown.
function Read-LoginFromDialog([string]$problem) {
    try {
        $dialog = New-LoginDialog $problem
    } catch {
        Write-Host "Could not show the sign-in window: $($_.Exception.Message)"
        return $null
    }
    try {
        if ($dialog.Form.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { return $null }
        return @{ User = (Get-NormalUser $dialog.User.Text); Password = $dialog.Password.Text
                  From = 'the sign-in window'; Save = $true }
    } finally {
        $dialog.Form.Dispose()
    }
}

# The shortcut's splash closes before a failure is shown (splash.ps1 watches this file; desktop.Splash).
function Close-Splash {
    try {
        $ready = Join-Path $env:USERPROFILE '.cat\java\splash.ready'
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ready) | Out-Null
        [IO.File]::WriteAllText($ready, [DateTime]::UtcNow.ToString('o'))
    } catch {
        Write-Host "Could not close the splash: $($_.Exception.Message)"
    }
}

# A message box in front of everything, the splash closed first; never throws.
function Show-Notice([string]$text) {
    Close-Splash
    try {
        Add-Type -AssemblyName System.Windows.Forms
        $owner = New-Object Windows.Forms.Form
        $owner.TopMost = $true
        try {
            [void][Windows.Forms.MessageBox]::Show($owner, $text, 'CAT-Java', [Windows.Forms.MessageBoxButtons]::OK,
                [Windows.Forms.MessageBoxIcon]::Warning)
        } finally {
            $owner.Dispose()
        }
    } catch {
        Write-Host "Could not show a message: $($_.Exception.Message)"
    }
}

# Staging failed: said on the console and, under the headless console, in a message box too. Returns 3
# when a window has told the operator (run.cmd then adds nothing of its own), else 1.
function Stop-Staging([string]$text) {
    Write-Host $text
    if (Test-Headless) {
        Show-Notice ($text + "`n`nCAT-Java cannot open without Java 17.")
        return 3
    }
    return 1
}

# --- asking for the login ---------------------------------------------------------------------------

# In the console, or in the sign-in window under the headless console; $null when it cannot be asked
# (input redirected: an agent or a scheduled task) or the operator gives none.
function Read-LoginFromOperator([string]$problem) {
    if (Test-Headless) { return (Read-LoginFromDialog $problem) }
    if ([Console]::IsInputRedirected) { return $null }
    Write-Host ''
    Write-Host 'CAT needs your Artifactory login once, to download Java 17. It is saved encrypted for this'
    Write-Host "Windows account only, in $CatJavaLogin."
    $user = Read-Host 'Artifactory user (your Verifone login)'
    if (-not $user) { return $null }
    $secure = Read-Host 'Artifactory password or API key' -AsSecureString
    $password = ConvertTo-Plain $secure
    if (-not $password) { return $null }
    return @{ User = (Get-NormalUser $user); Password = $password; From = 'what you typed'; Save = $true }
}

function Save-Login($login) {
    $dir = Split-Path -Parent $CatJavaLogin
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $secure = ConvertTo-SecureString -String $login.Password -AsPlainText -Force
    $json = @{ Username = $login.User; Password = (ConvertFrom-SecureString -SecureString $secure) } |
        ConvertTo-Json -Compress
    [IO.File]::WriteAllText($CatJavaLogin, $json, (New-Object Text.UTF8Encoding($false)))
    Write-Host "Saved the Artifactory login to $CatJavaLogin"
}

function Get-AuthHeader($login) {
    $pair = [Text.Encoding]::UTF8.GetBytes($login.User + ':' + $login.Password)
    return @{ Authorization = ('Basic ' + [Convert]::ToBase64String($pair)) }
}

# The HTTP status of a failed request, or 0 when there was no answer.
function Get-Status($err) {
    $response = $err.Exception.Response
    if ($response -and $response.StatusCode) { return [int]$response.StatusCode }
    return 0
}

# --- Java 17 ----------------------------------------------------------------------------------------

function Install-Java17 {
    $target = Join-Path $ToolsRoot 'Java17'
    $javaExe = Join-Path $target "$Jdk\bin\java.exe"
    if (Test-Path -LiteralPath $javaExe -PathType Leaf) { return 0 }

    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor
        [Net.SecurityProtocolType]::Tls12
    [Net.WebRequest]::DefaultWebProxy.Credentials = [Net.CredentialCache]::DefaultNetworkCredentials

    Write-Host "No Java 17 at $javaExe; staging Java 17 v1.2 from Artifactory."
    $login = Get-SavedLogin
    $problem = ''
    $info = $null
    for ($attempt = 0; $attempt -lt 3 -and -not $info; $attempt++) {
        if (-not $login) { $login = Read-LoginFromOperator $problem }
        if (-not $login) {
            if (Test-Headless) { Write-Host 'The sign-in window was cancelled.'; return 3 }
            Write-Host 'No Artifactory login: set ARTIFACTORY_USER and ARTIFACTORY_PASSWORD, or run build.cmd'
            Write-Host 'or run.cmd from a console window so it can ask for one.'
            return 1
        }
        try {
            $info = Invoke-RestMethod -UseBasicParsing -Headers (Get-AuthHeader $login) `
                -Uri "$ArtifactoryBase/api/storage/$JavaRepoPath"
        } catch {
            $status = Get-Status $_
            if ($status -eq 401 -or $status -eq 403) {
                $problem = "Artifactory refused the login from $($login.From) (HTTP $status). Check it and try again."
                Write-Host $problem
                $login = $null
                continue
            }
            return (Stop-Staging "Could not reach Artifactory for $JavaRepoPath`: $($_.Exception.Message)")
        }
    }
    if (-not $info) { return (Stop-Staging "Artifactory refused the login three times. $problem") }
    if ($login.Save) { Save-Login $login }

    New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
    $scratch = Join-Path $ToolsRoot ('.cat-fetch-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Path $scratch | Out-Null
    try {
        $zip = Join-Path $scratch 'Java17.zip'
        $mb = [Math]::Round([double]$info.size / 1MB)
        Write-Host "Downloading $JavaRepoPath ($mb MB)..."
        Invoke-WebRequest -UseBasicParsing -Headers (Get-AuthHeader $login) `
            -Uri "$ArtifactoryBase/$JavaRepoPath" -OutFile $zip
        if ($info.size -and (Get-Item -LiteralPath $zip).Length -ne [long]$info.size) {
            return (Stop-Staging ("The Java 17 download was cut short: $((Get-Item -LiteralPath $zip).Length) of " +
                "$($info.size) bytes."))
        }
        $expected = $info.checksums.sha256
        if ($expected) {
            $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
            if ($actual -ne $expected.ToUpperInvariant()) {
                return (Stop-Staging "Java17.zip's sha256 is $actual; Artifactory says $expected. Nothing was staged.")
            }
        }
        Write-Host 'Unpacking...'
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $unpacked = Join-Path $scratch 'unpacked'
        [IO.Compression.ZipFile]::ExtractToDirectory($zip, $unpacked)
        $staged = Join-Path $unpacked $JavaHolds
        if (-not (Test-Path -LiteralPath (Join-Path $staged "$Jdk\bin\java.exe") -PathType Leaf)) {
            return (Stop-Staging "Java17.zip holds no $JavaHolds\$Jdk\bin\java.exe. Nothing was staged.")
        }
        if (Test-Path -LiteralPath $target) {
            # Something else under Java17 without the JDK CAT runs on: kept, renamed, never deleted (JavaSets.stage).
            $aside = "$target.not-v1.2-" + [Guid]::NewGuid().ToString('N').Substring(0, 8)
            Move-Item -LiteralPath $target -Destination $aside
            Write-Host "$target held no $Jdk; moved it to $aside"
        }
        Move-Item -LiteralPath $staged -Destination $target
        Write-Host "Java 17 v1.2 unpacked into $target"
        return 0
    } catch {
        return (Stop-Staging "Staging Java 17 failed: $($_.Exception.Message)")
    } finally {
        Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- dispatch ---------------------------------------------------------------------------------------

if ($NeedsBuild) {
    $reason = Get-BuildReason
    if ($reason) { Write-Host "Build needed: $reason."; exit 10 }
    exit 0
}
if ($MarkBuilt) {
    $jar = Join-Path $Repo 'target\cat-java.jar'
    if (Test-Path -LiteralPath $jar -PathType Leaf) { (Get-Item -LiteralPath $jar).LastWriteTimeUtc = [DateTime]::UtcNow }
    exit 0
}
if ($Clicked) {
    if (Test-Clicked) { exit 0 }
    exit 1
}
if ($Failed) {
    if (Test-Clicked) { exit 0 }
    if (Test-Headless) {
        Show-Notice ("CAT-Java could not be built or started.`n`nDouble-click $Repo\run.cmd to see why: " +
            'its window stays open when something fails.')
    }
    exit 1
}
if ($FailedTests) {
    $reports = Join-Path $Repo 'target\surefire-reports'
    if (Test-Path -LiteralPath $reports) {
        foreach ($report in (Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml')) {
            $head = (Get-Content -LiteralPath $report.FullName -TotalCount 3) -join ' '
            if ($head -match '(failures|errors)="[1-9]') { Write-Host ('  ' + $report.BaseName.Substring(5)) }
        }
    }
    exit 0
}
if ($Java) { exit (Install-Java17) }
Write-Host ('usage: bootstrap.ps1 -Java [-ToolsRoot DIR] | -NeedsBuild | -MarkBuilt | -FailedTests | -Clicked' +
    ' | -Failed')
exit 2

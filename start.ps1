$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$qtRoot = Join-Path $PSScriptRoot 'qt'
$qtBin = Join-Path $qtRoot 'bin'
$qtPlugins = Join-Path $qtRoot 'plugins'
$qtCore = Join-Path $qtBin 'Qt6Core.dll'
$qtWindowsPlugin = Join-Path $qtPlugins 'platforms\qwindows.dll'
if (-not (Test-Path -LiteralPath $qtCore) -or -not (Test-Path -LiteralPath $qtWindowsPlugin)) {
    throw "Local Qt 6.12 runtime is missing. Expected: $qtCore and $qtWindowsPlugin"
}
$env:Path = "$qtBin;$env:Path"
$env:QT_PLUGIN_PATH = $qtPlugins

$logDirectory = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$applicationLog = Join-Path $logDirectory 'application.log'

if (-not (Test-Path -LiteralPath "$PSScriptRoot\config.properties")) {
    if ([string]::IsNullOrWhiteSpace($env:TWITCH_CHANNEL)) {
        $env:TWITCH_CHANNEL = Read-Host 'Twitch channel name or URL'
    }

    if ([string]::IsNullOrWhiteSpace($env:TWITCH_BOT_USERNAME)) {
        $env:TWITCH_BOT_USERNAME = Read-Host 'Bot account username'
    }

    if ([string]::IsNullOrWhiteSpace($env:TWITCH_OAUTH_TOKEN)) {
        $secureToken = Read-Host 'Bot OAuth token (input is hidden)' -AsSecureString
        $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
        try {
            $env:TWITCH_OAUTH_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
        }
        finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
        }
    }
}

try {
    Write-Host ''
    Write-Host '=== Twitch Chat for ADHDs ===' -ForegroundColor Cyan
    Write-Host '[1/2] Preparing the current version. This may take a moment...' -ForegroundColor Cyan

    # Capture Gradle output as ordinary text. Windows PowerShell otherwise turns
    # harmless javac notes written to stderr into alarming NativeCommandError blocks.
    $gradleCommand = Join-Path $PSScriptRoot 'gradlew.bat'
    $buildCommandLine = '/d /s /c ""{0}" --no-daemon --quiet --console=plain --warning-mode=none installDist"' -f $gradleCommand
    $buildStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $buildStartInfo.FileName = $env:ComSpec
    $buildStartInfo.Arguments = $buildCommandLine
    $buildStartInfo.WorkingDirectory = $PSScriptRoot
    $buildStartInfo.UseShellExecute = $false
    $buildStartInfo.CreateNoWindow = $true
    $buildStartInfo.RedirectStandardOutput = $true
    $buildStartInfo.RedirectStandardError = $true
    $buildProcess = [System.Diagnostics.Process]::new()
    $buildProcess.StartInfo = $buildStartInfo
    [void]$buildProcess.Start()
    $buildOutputTask = $buildProcess.StandardOutput.ReadToEndAsync()
    $buildErrorTask = $buildProcess.StandardError.ReadToEndAsync()
    $buildProcess.WaitForExit()
    $buildExitCode = $buildProcess.ExitCode
    $buildOutput = $buildOutputTask.GetAwaiter().GetResult()
    $buildError = $buildErrorTask.GetAwaiter().GetResult()
    $buildProcess.Dispose()

    $buildLogEntry = "`r`n=== Build $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===`r`n$buildOutput$buildError"
    [System.IO.File]::AppendAllText($applicationLog, $buildLogEntry, $utf8)
    if ($buildExitCode -ne 0) {
        Write-Host ''
        Write-Host 'BUILD FAILED. The application was not started.' -ForegroundColor Red
        $buildDetails = ($buildOutput + "`r`n" + $buildError) -split '\r?\n' |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -Last 40
        $buildDetails | ForEach-Object { Write-Host $_ }
        Write-Host "Full build output: $applicationLog" -ForegroundColor Yellow
        [void](Read-Host 'Press Enter to close')
        exit $buildExitCode
    }

    Write-Host ''
    Write-Host '[1/2] Current version is ready.' -ForegroundColor Green
    Write-Host '[2/2] Starting Twitch Chat for ADHDs...' -ForegroundColor Cyan

    # Start the GUI independently so this launcher can close. Runtime details are
    # written to log files instead of filling the terminal with startup diagnostics.
    $javaCommand = Get-Command javaw.exe -ErrorAction Stop
    $libraryPath = "$PSScriptRoot\build\install\twitch-chat-for-adhds\lib\*"
    $javaArguments = @(
        '-Xms64m',
        '-Xmx512m',
        '--enable-native-access=ALL-UNNAMED',
        '-Dfile.encoding=UTF-8',
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        "-Djava.library.path=$qtBin",
        '--sun-misc-unsafe-memory-access=allow',
        '-cp',
        $libraryPath,
        'pl.example.twitchbot.Main'
    )
    $javaArgumentLine = ($javaArguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + $_.Replace('"', '\"') + '"'
        }
        else {
            $_
        }
    }) -join ' '
    $runtimeLog = Join-Path $logDirectory 'runtime.log'
    $runtimeErrorLog = Join-Path $logDirectory 'runtime-errors.log'
    $applicationProcess = Start-Process `
        -FilePath $javaCommand.Source `
        -ArgumentList $javaArgumentLine `
        -WorkingDirectory $PSScriptRoot `
        -RedirectStandardOutput $runtimeLog `
        -RedirectStandardError $runtimeErrorLog `
        -PassThru

    Start-Sleep -Milliseconds 800
    if ($applicationProcess.HasExited -and $applicationProcess.ExitCode -ne 0) {
        Write-Host ''
        Write-Host "APPLICATION COULD NOT START (exit code $($applicationProcess.ExitCode))." -ForegroundColor Red
        Write-Host "Details: $runtimeErrorLog" -ForegroundColor Yellow
        [void](Read-Host 'Press Enter to close')
        exit $applicationProcess.ExitCode
    }

    Write-Host '[2/2] Application started. Closing this window...' -ForegroundColor Green
    exit 0
}
finally {
    Remove-Item Env:TWITCH_OAUTH_TOKEN -ErrorAction SilentlyContinue
}

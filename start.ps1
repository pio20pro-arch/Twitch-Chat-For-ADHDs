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
    throw "Brak lokalnego runtime Qt 6.12. Oczekiwano: $qtCore oraz $qtWindowsPlugin"
}
$env:Path = "$qtBin;$env:Path"
$env:QT_PLUGIN_PATH = $qtPlugins

$logDirectory = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$applicationLog = Join-Path $logDirectory 'application.log'
$errorLog = Join-Path $logDirectory 'error.log'

if (-not (Test-Path -LiteralPath "$PSScriptRoot\config.properties")) {
    if ([string]::IsNullOrWhiteSpace($env:TWITCH_CHANNEL)) {
        $env:TWITCH_CHANNEL = Read-Host 'Nazwa kanalu lub adres Twitch'
    }

    if ([string]::IsNullOrWhiteSpace($env:TWITCH_BOT_USERNAME)) {
        $env:TWITCH_BOT_USERNAME = Read-Host 'Login konta bota'
    }

    if ([string]::IsNullOrWhiteSpace($env:TWITCH_OAUTH_TOKEN)) {
        $secureToken = Read-Host 'Token OAuth bota (tekst bedzie ukryty)' -AsSecureString
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
    & "$PSScriptRoot\gradlew.bat" --no-daemon --console=plain installDist *>> $applicationLog
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    # Gradle kończy pracę przed uruchomieniem bota, więc przez wiele godzin
    # pozostaje tylko jeden proces Javy z ograniczoną stertą.
    $javaCommand = Get-Command javaw.exe -ErrorAction Stop
    $libraryPath = "$PSScriptRoot\build\install\twitch-chat-responder\lib\*"
    & $javaCommand.Source `
        '-Xms64m' `
        '-Xmx512m' `
        '--enable-native-access=ALL-UNNAMED' `
        '-Dfile.encoding=UTF-8' `
        '-Dstdout.encoding=UTF-8' `
        '-Dstderr.encoding=UTF-8' `
        "-Djava.library.path=$qtBin" `
        '--sun-misc-unsafe-memory-access=allow' `
        '-cp' $libraryPath `
        'pl.example.twitchbot.Main' `
        1>> $applicationLog `
        2>> $errorLog
    exit $LASTEXITCODE
}
finally {
    Remove-Item Env:TWITCH_OAUTH_TOKEN -ErrorAction SilentlyContinue
}

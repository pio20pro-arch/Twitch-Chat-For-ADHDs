@echo off
setlocal
cd /d "%~dp0"

if not exist "start.ps1" (
  echo Brak pliku start.ps1.
  exit /b 1
)

start "" powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0start.ps1"
endlocal

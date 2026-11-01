@echo off
rem ASCII-only launcher so it works under any console code page.
rem The Chinese output lives in install-apk.ps1 (UTF-8 with BOM).
setlocal
cd /d "%~dp0"

where powershell >nul 2>&1
if errorlevel 1 (
    echo [X] powershell.exe not found. Windows 7 SP1 or later is required.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-apk.ps1"
set "RC=%ERRORLEVEL%"
exit /b %RC%

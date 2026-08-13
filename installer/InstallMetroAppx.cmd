@echo off
setlocal
title MSC Launcher Tiles 3.0 Installer
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install.ps1"
if errorlevel 1 (
    echo.
    echo Installation failed. See the error above.
    pause
    exit /b 1
)
echo.
echo Installation completed successfully.
pause

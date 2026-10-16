@echo off
setlocal
title RoboParts - Servidor seguro local
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-SecureBackend.ps1"
echo.
pause
endlocal

@echo off
setlocal
title RoboParts - Configurar seguranca
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Configure-Security.ps1"
echo.
pause
endlocal

@echo off
setlocal
title RoboParts - Copiar banco para Neon
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Copy-DatabaseToNeon.ps1"
echo.
pause
endlocal

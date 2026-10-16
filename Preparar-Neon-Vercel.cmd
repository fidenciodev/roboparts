@echo off
setlocal
title RoboParts - Preparar Neon para Vercel
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Configure-NeonRuntime.ps1"
echo.
pause
endlocal

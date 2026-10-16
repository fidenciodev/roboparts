@echo off
setlocal
title RoboParts - Configurar Gmail
cd /d "%~dp0"
echo RoboParts - configuracao do envio de codigos por Gmail
echo.
echo No Google, ative a verificacao em duas etapas e crie uma senha de app.
echo A senha de app sera digitada somente neste terminal, de forma oculta.
echo Esta janela tambem pedira a senha do PostgreSQL e o codigo interno de cadastro.
echo Deixe a janela aberta depois que o servidor iniciar.
echo.
start "" "https://myaccount.google.com/apppasswords"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Backend.ps1" -ConfigureEmail
if errorlevel 1 (
  echo.
  echo O servidor nao iniciou. Confira a mensagem acima.
  pause
)
endlocal

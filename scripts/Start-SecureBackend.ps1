[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
. (Join-Path $PSScriptRoot 'DatabaseEnvironment.ps1')
$project=Split-Path -Parent $PSScriptRoot
$keys=@('DB_URL','DB_USERNAME','DB_PASSWORD','DB_AUTO_MIGRATE','MAIL_HOST','MAIL_PORT','MAIL_USERNAME','MAIL_PASSWORD','MAIL_FROM','SECURITY_MANAGERS','SECURITY_KEY_FILE','REGISTRATION_REQUIRE_INVITATION','REGISTRATION_ACCESS_CODE','DEBUG','TRACE','LOGGING_LEVEL_ROOT','SESSION_TIMEOUT')
$previous=@{};foreach($name in $keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$writer=$null
try {
    $settings=Get-RoboPartsPrivateSettings
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)
    if($listeners.Count-gt 0){throw 'A porta 8080 esta em uso. Encerre a janela do servidor antigo antes de iniciar.'}
    $env:DB_URL='jdbc:postgresql://127.0.0.1:5432/roboparts';$env:DB_USERNAME='roboparts_app';$env:DB_AUTO_MIGRATE='false'
    $env:SECURITY_MANAGERS=$settings.ManagerEmail;$env:SECURITY_KEY_FILE=$settings.SecurityKeyFile;$env:REGISTRATION_REQUIRE_INVITATION='true'
    $env:MAIL_HOST='smtp.gmail.com';$env:MAIL_PORT='587';$env:MAIL_USERNAME=$settings.MailUsername;$env:MAIL_FROM=$settings.MailUsername
    foreach($mapping in @(@('app-database','DB_PASSWORD'),@('gmail','MAIL_PASSWORD'),@('registration','REGISTRATION_ACCESS_CODE'))){
        $secret=Read-RoboPartsStoredSecret -Name $mapping[0]
        [Environment]::SetEnvironmentVariable($mapping[1],(ConvertFrom-RoboPartsSecret $secret),'Process');$secret.Dispose()
    }
    $env:DEBUG='false';$env:TRACE='false';$env:LOGGING_LEVEL_ROOT='INFO'
    $env:SESSION_TIMEOUT='30m'
    $log=Join-Path $project 'backend\target\roboparts-startup.log'
    $writer=[IO.StreamWriter]::new($log,$false,[Text.UTF8Encoding]::new($false));$writer.AutoFlush=$true
    Write-Host 'RoboParts iniciando com usuario restrito e configuracao privada. Deixe esta janela aberta.'
    $jar=Join-Path $project 'backend\target\roboparts-backend-0.1.0-SNAPSHOT.jar'
    & (Get-Command java.exe).Source '-jar' $jar 2>&1|ForEach-Object{
        $line=Protect-RoboPartsStartupLog -Message $_.ToString() -Secrets @($env:DB_PASSWORD,$env:MAIL_PASSWORD,$env:REGISTRATION_ACCESS_CODE)
        $writer.WriteLine($line);Write-Host $line
    }
    exit $LASTEXITCODE
}catch{Write-Error 'O servidor nao iniciou. Confira se encerrou a janela antiga e se concluiu Configurar-Seguranca.cmd; dados privados omitidos.' -ErrorAction Continue;exit 1}
finally{if($null-ne $writer){$writer.Dispose()};foreach($name in $keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}

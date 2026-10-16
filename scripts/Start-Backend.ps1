[CmdletBinding()]
param([switch]$DisableRegistration, [switch]$ConfigureEmail)

$ErrorActionPreference = 'Stop'
$privateSettings=Join-Path $env:LOCALAPPDATA 'RoboParts\settings.json'
if(Test-Path -LiteralPath $privateSettings -PathType Leaf) {
    if($ConfigureEmail -or $DisableRegistration) {
        Write-Host 'A configuracao privada esta ativa. Para alterar Gmail ou acessos, execute Configurar-Seguranca.cmd.'
        exit 2
    }
    & (Join-Path $PSScriptRoot 'Start-SecureBackend.ps1')
    exit $LASTEXITCODE
}
# Legacy manual startup still needs an explicit responsible mailbox for strict invitations.
if([string]::IsNullOrWhiteSpace($env:SECURITY_MANAGERS)) {
    Write-Host 'Primeiro configure o responsavel, o banco restrito e os backups com Configurar-Seguranca.cmd.'
    exit 2
}
. (Join-Path $PSScriptRoot 'DatabaseEnvironment.ps1')
. (Join-Path $PSScriptRoot 'EmailEnvironment.ps1')
$backendDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend'
$temporaryPassword = $false
$temporaryRegistrationCode = $false
$previousRegistrationCode = $env:REGISTRATION_ACCESS_CODE
$locationPushed = $false
$exitCode = 0
$logWriter = $null
$previousEmailEnvironment = $null

try {
    $temporaryPassword = Initialize-RoboPartsDatabasePassword -PromptForPassword
    if ($DisableRegistration) {
        [Environment]::SetEnvironmentVariable('REGISTRATION_ACCESS_CODE', $null, 'Process')
    }
    else {
        $temporaryRegistrationCode = Initialize-RoboPartsRegistrationCode -PromptForCode
    }
    if ($ConfigureEmail) { $previousEmailEnvironment = Initialize-RoboPartsGmail }
    elseif ([string]::IsNullOrWhiteSpace($env:MAIL_USERNAME) -or [string]::IsNullOrWhiteSpace($env:MAIL_PASSWORD)) {
        Write-Host 'Envio de codigos de e-mail nao configurado. Use -ConfigureEmail para configurar o Gmail localmente.'
    }
    $mavenCommand = Get-RoboPartsMavenCommand -BackendDirectory $backendDirectory
    $logDirectory = Join-Path $backendDirectory 'target'
    [System.IO.Directory]::CreateDirectory($logDirectory) | Out-Null
    $logPath = Join-Path $logDirectory 'roboparts-startup.log'
    $logWriter = [System.IO.StreamWriter]::new($logPath, $false, [System.Text.UTF8Encoding]::new($false))
    $logWriter.AutoFlush = $true
    Write-Host "Log de inicializacao: $logPath"
    Push-Location -LiteralPath $backendDirectory
    $locationPushed = $true

    # Backend preflight validates existing objects before Flyway can migrate.
    $ErrorActionPreference = 'Continue'
    & $mavenCommand 'spring-boot:run' 2>&1 | ForEach-Object {
        $safeLine = Protect-RoboPartsStartupLog -Message $_.ToString() -Secrets @($env:DB_PASSWORD, $env:REGISTRATION_ACCESS_CODE, $env:MAIL_PASSWORD)
        $logWriter.WriteLine($safeLine)
        Write-Host $safeLine
    }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
}
catch {
    if ($exitCode -eq 0) { $exitCode = 1 }
    $safeError = Protect-RoboPartsStartupLog -Message $_.Exception.Message -Secrets @($env:DB_PASSWORD, $env:REGISTRATION_ACCESS_CODE, $env:MAIL_PASSWORD)
    if ($null -ne $logWriter) { $logWriter.WriteLine($safeError) }
    Write-Error -Message $safeError -ErrorAction Continue
}
finally {
    if ($null -ne $logWriter) { $logWriter.Dispose() }
    if ($locationPushed) { Pop-Location }
    Restore-RoboPartsEmailEnvironment -Previous $previousEmailEnvironment
    Clear-RoboPartsTemporaryPassword -WasInitialized $temporaryPassword
    if ($DisableRegistration) {
        [Environment]::SetEnvironmentVariable('REGISTRATION_ACCESS_CODE', $previousRegistrationCode, 'Process')
    }
    else {
        Clear-RoboPartsTemporaryRegistrationCode -WasInitialized $temporaryRegistrationCode
    }
}

exit $exitCode

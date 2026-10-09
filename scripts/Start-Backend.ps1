[CmdletBinding()]
param([switch]$DisableRegistration)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'DatabaseEnvironment.ps1')
$backendDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend'
$temporaryPassword = $false
$temporaryRegistrationCode = $false
$previousRegistrationCode = $env:REGISTRATION_ACCESS_CODE
$locationPushed = $false
$exitCode = 0
$logWriter = $null

try {
    $temporaryPassword = Initialize-RoboPartsDatabasePassword -PromptForPassword
    if ($DisableRegistration) {
        [Environment]::SetEnvironmentVariable('REGISTRATION_ACCESS_CODE', $null, 'Process')
    }
    else {
        $temporaryRegistrationCode = Initialize-RoboPartsRegistrationCode -PromptForCode
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
        $safeLine = Protect-RoboPartsStartupLog -Message $_.ToString() -Secrets @($env:DB_PASSWORD, $env:REGISTRATION_ACCESS_CODE)
        $logWriter.WriteLine($safeLine)
        Write-Host $safeLine
    }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
}
catch {
    if ($exitCode -eq 0) { $exitCode = 1 }
    $safeError = Protect-RoboPartsStartupLog -Message $_.Exception.Message -Secrets @($env:DB_PASSWORD, $env:REGISTRATION_ACCESS_CODE)
    if ($null -ne $logWriter) { $logWriter.WriteLine($safeError) }
    Write-Error -Message $safeError -ErrorAction Continue
}
finally {
    if ($null -ne $logWriter) { $logWriter.Dispose() }
    if ($locationPushed) { Pop-Location }
    Clear-RoboPartsTemporaryPassword -WasInitialized $temporaryPassword
    if ($DisableRegistration) {
        [Environment]::SetEnvironmentVariable('REGISTRATION_ACCESS_CODE', $previousRegistrationCode, 'Process')
    }
    else {
        Clear-RoboPartsTemporaryRegistrationCode -WasInitialized $temporaryRegistrationCode
    }
}

exit $exitCode

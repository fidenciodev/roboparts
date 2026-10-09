[CmdletBinding()]
param(
    [switch]$Postgres,
    [switch]$PromptForPassword
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'DatabaseEnvironment.ps1')
$backendDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend'
$temporaryPassword = $false
$locationPushed = $false
$previousIntegrationFlag = $env:ROBOPARTS_POSTGRES_IT
$exitCode = 0

try {
    $mavenCommand = Get-RoboPartsMavenCommand -BackendDirectory $backendDirectory
    if ($Postgres) {
        $temporaryPassword = Initialize-RoboPartsDatabasePassword -PromptForPassword:$PromptForPassword
        if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
            throw 'Defina DB_PASSWORD ou use -PromptForPassword para executar os testes PostgreSQL.'
        }
        $env:ROBOPARTS_POSTGRES_IT = 'true'
    }
    Push-Location -LiteralPath $backendDirectory
    $locationPushed = $true
    $ErrorActionPreference = 'Continue'
    if ($Postgres) {
        # Opt-in tests read an already migrated database and do not run migrations.
        & $mavenCommand '-Ppostgres-it' 'verify'
    }
    else {
        & $mavenCommand 'test'
    }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
}
catch {
    if ($exitCode -eq 0) { $exitCode = 1 }
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
}
finally {
    if ($locationPushed) { Pop-Location }
    if ($Postgres) {
        [Environment]::SetEnvironmentVariable('ROBOPARTS_POSTGRES_IT', $previousIntegrationFlag, 'Process')
    }
    Clear-RoboPartsTemporaryPassword -WasInitialized $temporaryPassword
}

exit $exitCode

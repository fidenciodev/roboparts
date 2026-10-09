[CmdletBinding()]
param([switch]$PromptForPassword)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'DatabaseEnvironment.ps1')
$backendDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend'
$temporaryPassword = $false
$locationPushed = $false
$exitCode = 0

try {
    $temporaryPassword = Initialize-RoboPartsDatabasePassword -PromptForPassword:$PromptForPassword
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
        Write-Host 'DB_PASSWORD nao definida. Nenhuma conexao ou alteracao realizada.'
        exit 2
    }
    $mavenCommand = Get-RoboPartsMavenCommand -BackendDirectory $backendDirectory
    $javaCommand = (Get-Command 'java.exe' -ErrorAction Stop).Source
    Push-Location -LiteralPath $backendDirectory
    $locationPushed = $true

    # Compiles the inspection CLI and resolves dependencies without starting Spring.
    $ErrorActionPreference = 'Continue'
    & $mavenCommand '-B' '-q' '-DskipTests' 'compile' 'dependency:build-classpath' '-Dmdep.includeScope=runtime' '-Dmdep.outputFile=target/database-inspection-classpath.txt'
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) {
        $exitCode = $LASTEXITCODE
        throw 'Falha ao preparar a ferramenta de inspecao.'
    }

    $classpathFile = Join-Path $backendDirectory 'target/database-inspection-classpath.txt'
    $runtimeClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $compiledClasses = Join-Path $backendDirectory 'target/classes'
    $inspectionClasspath = $compiledClasses + [IO.Path]::PathSeparator + $runtimeClasspath
    # Windows PowerShell treats native stderr as an ErrorRecord. Preserve the
    # CLI exit code instead of turning its diagnostic into a script exception.
    $ErrorActionPreference = 'Continue'
    & $javaCommand '-cp' $inspectionClasspath 'br.com.roboparts.tools.InspectDatabase'
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
}
catch {
    if ($exitCode -eq 0) { $exitCode = 1 }
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
}
finally {
    if ($locationPushed) { Pop-Location }
    Clear-RoboPartsTemporaryPassword -WasInitialized $temporaryPassword
}

exit $exitCode

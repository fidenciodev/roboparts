[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$frontendDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'frontend'
$locationPushed = $false
$exitCode = 0

try {
    $npmCommand = (Get-Command 'npm.cmd' -ErrorAction Stop).Source
    Push-Location -LiteralPath $frontendDirectory
    $locationPushed = $true
    & $npmCommand 'run' 'dev'
    $exitCode = $LASTEXITCODE
}
catch {
    if ($exitCode -eq 0) { $exitCode = 1 }
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
}
finally {
    if ($locationPushed) { Pop-Location }
}

exit $exitCode

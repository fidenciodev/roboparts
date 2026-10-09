Set-StrictMode -Version Latest

function Protect-RoboPartsStartupLog {
    param(
        [AllowEmptyString()][string]$Message,
        [AllowNull()][string[]]$Secrets
    )

    # Literal replacement also handles passwords containing regex characters.
    $safeMessage = $Message
    foreach ($secret in ($Secrets | Where-Object { -not [string]::IsNullOrEmpty($_) } | Sort-Object { $_.Length } -Descending)) {
        $safeMessage = $safeMessage.Replace($secret, '[segredo oculto]')
    }
    return $safeMessage
}

function Get-RoboPartsMavenCommand {
    param([Parameter(Mandatory = $true)][string]$BackendDirectory)

    $wrapperPath = Join-Path $BackendDirectory 'mvnw.cmd'
    if (Test-Path -LiteralPath $wrapperPath -PathType Leaf) {
        return $wrapperPath
    }
    return (Get-Command 'mvn.cmd' -ErrorAction Stop).Source
}

# Returns true only when this helper populated the current process variable.
# Callers remove that temporary value in finally; user variables are never changed.
function Initialize-RoboPartsDatabasePassword {
    param([switch]$PromptForPassword)

    if (-not [string]::IsNullOrEmpty($env:DB_PASSWORD)) {
        return $false
    }

    $userPassword = [Environment]::GetEnvironmentVariable('DB_PASSWORD', 'User')
    if (-not [string]::IsNullOrEmpty($userPassword)) {
        $env:DB_PASSWORD = $userPassword
        $userPassword = $null
        return $true
    }

    if (-not $PromptForPassword) {
        return $false
    }

    $securePassword = Read-Host 'Senha do PostgreSQL (DB_PASSWORD; entrada oculta)' -AsSecureString
    $passwordPointer = [IntPtr]::Zero
    try {
        if ($securePassword.Length -eq 0) {
            throw 'DB_PASSWORD vazia. Operacao cancelada.'
        }
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        $env:DB_PASSWORD = $plainPassword
        $plainPassword = $null
        return $true
    }
    finally {
        if ($passwordPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
        }
        $securePassword.Dispose()
    }
}

function Clear-RoboPartsTemporaryPassword {
    param([bool]$WasInitialized)

    if ($WasInitialized) {
        [Environment]::SetEnvironmentVariable('DB_PASSWORD', $null, 'Process')
    }
}

# Registration stays closed unless a server-side secret is configured.
function Initialize-RoboPartsRegistrationCode {
    param([switch]$PromptForCode)

    if (-not [string]::IsNullOrWhiteSpace($env:REGISTRATION_ACCESS_CODE)) {
        return $false
    }
    $userCode = [Environment]::GetEnvironmentVariable('REGISTRATION_ACCESS_CODE', 'User')
    if (-not [string]::IsNullOrWhiteSpace($userCode)) {
        $env:REGISTRATION_ACCESS_CODE = $userCode
        $userCode = $null
        return $true
    }
    if (-not $PromptForCode) { return $false }

    $secureCode = Read-Host 'Codigo interno de cadastro (24 a 256 caracteres; vazio desabilita cadastros)' -AsSecureString
    $codePointer = [IntPtr]::Zero
    try {
        if ($secureCode.Length -eq 0) { return $false }
        if ($secureCode.Length -lt 24 -or $secureCode.Length -gt 256) {
            throw 'O codigo de cadastro deve ter entre 24 e 256 caracteres.'
        }
        $codePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureCode)
        $plainCode = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($codePointer)
        $env:REGISTRATION_ACCESS_CODE = $plainCode
        $plainCode = $null
        return $true
    }
    finally {
        if ($codePointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($codePointer)
        }
        $secureCode.Dispose()
    }
}

function Clear-RoboPartsTemporaryRegistrationCode {
    param([bool]$WasInitialized)
    if ($WasInitialized) {
        [Environment]::SetEnvironmentVariable('REGISTRATION_ACCESS_CODE', $null, 'Process')
    }
}

Set-StrictMode -Version Latest

function Initialize-RoboPartsGmail {
    # Process-only configuration. Never writes a credential to disk or to User/Machine settings.
    $previous = @{}
    foreach ($key in @('MAIL_HOST', 'MAIL_PORT', 'MAIL_USERNAME', 'MAIL_PASSWORD', 'MAIL_FROM')) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    }
    $gmail = (Read-Host 'Gmail que enviara os codigos de confirmacao').Trim()
    if ($gmail -notmatch '^[^\s@]+@gmail\.com$') { throw 'Informe uma conta Gmail valida.' }
    $secret = Read-Host 'Senha de app do Gmail (16 caracteres; entrada oculta, nao e sua senha normal)' -AsSecureString
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) -replace '\s', ''
        if ($plain.Length -ne 16) { throw 'A senha de app do Gmail deve ter 16 caracteres.' }
        $env:MAIL_HOST = 'smtp.gmail.com'
        $env:MAIL_PORT = '587'
        $env:MAIL_USERNAME = $gmail.Trim().ToLowerInvariant()
        $env:MAIL_FROM = $env:MAIL_USERNAME
        $env:MAIL_PASSWORD = $plain
        $plain = $null
        return $previous
    }
    catch {
        Restore-RoboPartsEmailEnvironment -Previous $previous
        throw
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $secret.Dispose()
    }
}

function Restore-RoboPartsEmailEnvironment {
    param([AllowNull()][hashtable]$Previous)
    if ($null -eq $Previous) { return }
    foreach ($key in $Previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $Previous[$key], 'Process')
    }
}

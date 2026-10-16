Set-StrictMode -Version Latest

function Get-RoboPartsPrivateDirectory {
    $directory = Join-Path $env:LOCALAPPDATA 'RoboParts'
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    # Persist only a new DACL. Reusing Get-Acl through Set-Acl can also request
    # audit/central-policy privileges (SeSecurityPrivilege) in Windows PowerShell.
    # The owner and audit rules are not part of this change.
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.SetAccessRule($rule)
    [IO.Directory]::SetAccessControl($directory, $acl)
    return $directory
}
function Save-RoboPartsSecret {
    param([Parameter(Mandatory)][string]$Name,[Parameter(Mandatory)][Security.SecureString]$Secret)
    if ($Name -notmatch '^[a-z-]+$') { throw 'Nome de segredo invalido.' }
    $Secret | Export-Clixml -LiteralPath (Join-Path (Get-RoboPartsPrivateDirectory) ($Name + '.credential.xml')) -Encoding UTF8
}
function Read-RoboPartsStoredSecret {
    param([Parameter(Mandatory)][string]$Name)
    if ($Name -notmatch '^[a-z-]+$') { throw 'Nome de segredo invalido.' }
    $path = Join-Path (Get-RoboPartsPrivateDirectory) ($Name + '.credential.xml')
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Configuracao privada ausente. Execute Configurar-Seguranca.cmd.' }
    return Import-Clixml -LiteralPath $path
}
function ConvertFrom-RoboPartsSecret {
    param([Parameter(Mandatory)][Security.SecureString]$Secret)
    $pointer = [IntPtr]::Zero
    try { $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret); return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) } }
}
function New-RoboPartsRandomPassword {
    $bytes = New-Object byte[] 48
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes); return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
    finally { $random.Dispose(); [Array]::Clear($bytes,0,$bytes.Length) }
}
function Get-RoboPartsPostgresTool {
    param([ValidateSet('pg_dump','pg_restore','psql')][string]$Name)
    $command = Get-Command ($Name + '.exe') -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $path = Join-Path $env:ProgramFiles ('PostgreSQL\18\bin\' + $Name + '.exe')
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Instale as ferramentas do PostgreSQL 18 ou configure o PATH.' }
    return $path
}
function Invoke-RoboPartsPrivateProcess {
    param([Parameter(Mandatory)][string]$File,[Parameter(Mandatory)][string[]]$Arguments,[hashtable]$Environment=@{},[AllowNull()][string]$InputText)
    foreach ($argument in $Arguments) { if ($argument -match '["\r\n]') { throw 'Argumento de processo invalido.' } }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $File
    # Scheduled tasks default to System32. The Java source launcher scans the
    # current classpath directory, so always use the small tools directory.
    $info.WorkingDirectory = $PSScriptRoot
    $info.Arguments = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.RedirectStandardInput = $true
    foreach ($key in $Environment.Keys) { $info.EnvironmentVariables[$key] = $Environment[$key] }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    try {
        [void]$process.Start()
        $output = $process.StandardOutput.ReadToEndAsync(); $errors = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) { $process.StandardInput.WriteLine($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(1800000)) { $process.Kill(); throw 'O processo excedeu 30 minutos.' }
        # A daemon launcher (pg_ctl on Windows) may leave inherited pipe handles in
        # its child after exiting. Output is intentionally private and discarded.
        foreach($stream in @(@($output,$process.StandardOutput),@($errors,$process.StandardError))) {
            if($stream[0].IsCompleted){[void]$stream[0].GetAwaiter().GetResult()}
            else {$stream[1].Close()}
        }
        if ($process.ExitCode -ne 0) { throw 'A operacao nao foi concluida. Confira configuracao e permissoes; detalhes privados omitidos.' }
    } finally { $process.Dispose() }
}
function Get-RoboPartsPrivateSettings {
    $path = Join-Path (Get-RoboPartsPrivateDirectory) 'settings.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Execute Configurar-Seguranca.cmd primeiro.' }
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}

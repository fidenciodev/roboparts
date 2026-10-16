[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
$workingDirectory = $null
try {
    $settings = Get-RoboPartsPrivateSettings
    $backupDirectory = [IO.Path]::GetFullPath($settings.BackupDirectory)
    $projectDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
    $projectRoot=$projectDirectory.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    if (($backupDirectory+[IO.Path]::DirectorySeparatorChar).StartsWith($projectRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Os backups devem ficar fora do projeto.' }
    [IO.Directory]::CreateDirectory($backupDirectory) | Out-Null
    $workingDirectory = Join-Path (Get-RoboPartsPrivateDirectory) ('backup-work-' + [guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($workingDirectory) | Out-Null
    $dump = Join-Path $workingDirectory 'roboparts.dump'
    $dbSecret = Read-RoboPartsStoredSecret -Name 'backup-database'
    $dbPassword = ConvertFrom-RoboPartsSecret $dbSecret
    Invoke-RoboPartsPrivateProcess -File (Get-RoboPartsPostgresTool 'pg_dump') -Arguments @('--host=127.0.0.1','--port=5432','--username=roboparts_backup','--dbname=roboparts','--schema=roboparts','--format=custom','--no-owner','--no-acl','--no-password',('--file=' + $dump)) -Environment @{PGPASSWORD=$dbPassword}
    $dbPassword = $null; $dbSecret.Dispose()
    Copy-Item -LiteralPath $settings.SecurityKeyFile -Destination (Join-Path $workingDirectory 'security.key')
    $manifest = @{createdAt=[DateTime]::UtcNow.ToString('o'); database='roboparts'; format=1; dumpSha256=(Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash; keySha256=(Get-FileHash -LiteralPath $settings.SecurityKeyFile -Algorithm SHA256).Hash; manager=$settings.ManagerEmail}
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $workingDirectory 'manifest.json') -Encoding UTF8
    $package = Join-Path $workingDirectory 'backup.zip'
    Compress-Archive -LiteralPath @($dump,(Join-Path $workingDirectory 'security.key'),(Join-Path $workingDirectory 'manifest.json')) -DestinationPath $package
    $phrase = Read-RoboPartsStoredSecret -Name 'backup-phrase'
    $plainPhrase = ConvertFrom-RoboPartsSecret $phrase
    $encodedPhrase = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($plainPhrase)); $plainPhrase = $null
    $destination = Join-Path $backupDirectory ('roboparts-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.rpb')
    Invoke-RoboPartsPrivateProcess -File (Get-Command 'java.exe' -ErrorAction Stop).Source -Arguments @('-Xmx768m',(Join-Path $PSScriptRoot 'BackupCrypto.java'),'encrypt',$package,$destination) -InputText $encodedPhrase
    $encodedPhrase = $null; $phrase.Dispose()
    # Verify the encrypted file before reporting success or removing any plaintext temporary files.
    $restoredPackage = Join-Path $workingDirectory 'verification.zip'
    $phrase = Read-RoboPartsStoredSecret -Name 'backup-phrase'
    $encodedPhrase = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((ConvertFrom-RoboPartsSecret $phrase)))
    Invoke-RoboPartsPrivateProcess -File (Get-Command 'java.exe').Source -Arguments @('-Xmx768m',(Join-Path $PSScriptRoot 'BackupCrypto.java'),'decrypt',$destination,$restoredPackage) -InputText $encodedPhrase
    $encodedPhrase = $null; $phrase.Dispose()
    if ((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $restoredPackage -Algorithm SHA256).Hash) { throw 'A verificacao do backup falhou.' }
    ('SUCCESS ' + [DateTime]::UtcNow.ToString('o') + ' ' + $destination) | Set-Content -LiteralPath (Join-Path (Get-RoboPartsPrivateDirectory) 'backup-status.txt') -Encoding UTF8
    Write-Host ('Backup criptografado e verificado: ' + $destination)
}
catch {
    ('FAILURE ' + [DateTime]::UtcNow.ToString('o')) | Set-Content -LiteralPath (Join-Path (Get-RoboPartsPrivateDirectory) 'backup-status.txt') -Encoding UTF8
    Write-Error 'O backup nao foi concluido. Confira acesso ao banco, ferramentas, chave e espaco disponivel; credenciais omitidas.' -ErrorAction Continue
    exit 1
}
finally {
    if ($null -ne $workingDirectory) {
        $root = [IO.Path]::GetFullPath((Get-RoboPartsPrivateDirectory)).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        $resolved = [IO.Path]::GetFullPath($workingDirectory)
        if (-not $resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notmatch '^backup-work-[a-f0-9]{32}$') { throw 'Diretorio temporario fora do local esperado; limpeza recusada.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}

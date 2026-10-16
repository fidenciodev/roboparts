[CmdletBinding()]
param([Parameter(Mandatory)][string]$BackupFile,[Parameter(Mandatory)][string]$TargetDatabase)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
if ($TargetDatabase -notmatch '^roboparts_restore_test_[a-z0-9_]{1,40}$') { throw 'Use somente um banco novo com nome roboparts_restore_test_..., nunca o banco em uso.' }
$backup = (Resolve-Path -LiteralPath $BackupFile).Path
$temporary = Join-Path (Get-RoboPartsPrivateDirectory) ('restore-work-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporary) | Out-Null
try {
    $phrase = Read-Host 'Frase de protecao usada neste backup (entrada oculta)' -AsSecureString
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((ConvertFrom-RoboPartsSecret $phrase)))
    $package = Join-Path $temporary 'backup.zip'
    Invoke-RoboPartsPrivateProcess -File (Get-Command 'java.exe').Source -Arguments @('-Xmx768m',(Join-Path $PSScriptRoot 'BackupCrypto.java'),'decrypt',$backup,$package) -InputText $encoded
    $encoded = $null; $phrase.Dispose()
    # Authenticate first, then extract only the three expected files. Never follow archive paths.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($package)
    try {
        $names = @('roboparts.dump','manifest.json','security.key')
        if ($archive.Entries.Count -ne 3) { throw 'Formato de backup inesperado.' }
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notin $names) { throw 'Caminho inesperado no backup.' }
            $limit=if($entry.FullName -eq 'security.key'){32}elseif($entry.FullName -eq 'manifest.json'){16384}else{268435456}
            if($entry.Length -gt $limit){throw 'Arquivo do backup excede o limite esperado.'}
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $temporary $entry.FullName),$false)
        }
    } finally { $archive.Dispose() }
    $manifest = Get-Content -LiteralPath (Join-Path $temporary 'manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.database -ne 'roboparts' -or $manifest.format -ne 1 -or $manifest.dumpSha256 -ne (Get-FileHash -LiteralPath (Join-Path $temporary 'roboparts.dump') -Algorithm SHA256).Hash -or $manifest.keySha256 -ne (Get-FileHash -LiteralPath (Join-Path $temporary 'security.key') -Algorithm SHA256).Hash) { throw 'A integridade do backup nao foi confirmada.' }
    $admin = Read-Host 'Senha do postgres para criar o banco separado de teste (entrada oculta)' -AsSecureString
    $adminPassword = ConvertFrom-RoboPartsSecret $admin
    $sql = 'CREATE DATABASE ' + $TargetDatabase + ';'
    Invoke-RoboPartsPrivateProcess -File (Get-RoboPartsPostgresTool 'psql') -Arguments @('-X','--set=ON_ERROR_STOP=1','--host=127.0.0.1','--port=5432','--username=postgres','--dbname=postgres','--no-password') -Environment @{PGPASSWORD=$adminPassword} -InputText $sql
    Invoke-RoboPartsPrivateProcess -File (Get-RoboPartsPostgresTool 'pg_restore') -Arguments @('--host=127.0.0.1','--port=5432','--username=postgres',('--dbname=' + $TargetDatabase),'--no-owner','--no-acl','--exit-on-error','--single-transaction','--no-password',(Join-Path $temporary 'roboparts.dump')) -Environment @{PGPASSWORD=$adminPassword}
    Invoke-RoboPartsPrivateProcess -File (Get-RoboPartsPostgresTool 'psql') -Arguments @('-X','--set=ON_ERROR_STOP=1','--host=127.0.0.1','--port=5432','--username=postgres',('--dbname=' + $TargetDatabase),'--no-password') -Environment @{PGPASSWORD=$adminPassword} -InputText 'SELECT count(*) FROM roboparts.users; SELECT count(*) FROM roboparts.flyway_schema_history WHERE success=true;'
    $adminPassword=$null;$admin.Dispose()
    Write-Host ('Restauracao de teste concluida em ' + $TargetDatabase + '. O banco roboparts nao foi substituido.')
    Write-Host 'A chave de duas etapas foi verificada dentro do arquivo. Nao substitua a chave em uso durante um teste.'
} catch { Write-Error 'A restauracao de teste nao foi concluida. O banco em uso foi preservado; confira frase, destino e acesso.' -ErrorAction Continue; exit 1 }
finally {
    $root = [IO.Path]::GetFullPath((Get-RoboPartsPrivateDirectory)).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $resolved = [IO.Path]::GetFullPath($temporary)
    if (-not $resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notmatch '^restore-work-[a-f0-9]{32}$') { throw 'Limpeza fora do diretorio esperado recusada.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}

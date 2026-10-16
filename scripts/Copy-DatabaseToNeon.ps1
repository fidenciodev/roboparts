[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
. (Join-Path $PSScriptRoot 'NeonMigration.ps1')
$working = $null
$connectionSecret = $null
$sourceSecret = $null
$target = $null
$targetEnvironment = @{}
$stage = 'preparacao'
try {
    Write-Host 'RoboParts - Copiar o banco local para um Neon vazio'
    Write-Host 'No Neon: Connect > desligue Connection pooling > Copy snippet.'
    Write-Host 'Cole a connection string no campo oculto abaixo. Ela nao sera salva.'
    $connectionSecret = Read-Host 'Connection string DIRETA do Neon (entrada oculta)' -AsSecureString
    $target = ConvertFrom-RoboPartsNeonConnection (ConvertFrom-RoboPartsSecret $connectionSecret)
    $connectionSecret.Dispose(); $connectionSecret = $null
    $private = Get-RoboPartsPrivateDirectory
    $working = Join-Path $private ('neon-copy-' + [guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($working) | Out-Null
    $psql = Get-RoboPartsPostgresTool 'psql'
    $dumpTool = Get-RoboPartsPostgresTool 'pg_dump'
    $restoreTool = Get-RoboPartsPostgresTool 'pg_restore'
    $certificate = Join-Path $working 'neon-root.pem'
    $stage = 'certificado TLS'
    # Public CA recommended by Neon. HTTPS validates the download; verify-full
    # below validates the database certificate and hostname before sending data.
    $previousTls = [Net.ServicePointManager]::SecurityProtocol
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri 'https://letsencrypt.org/certs/isrgrootx1.pem' -UseBasicParsing -TimeoutSec 30 -OutFile $certificate
    } finally { [Net.ServicePointManager]::SecurityProtocol = $previousTls }
    $targetEnvironment = @{PGPASSWORD=$target.Password; PGSSLMODE='verify-full'; PGSSLROOTCERT=$certificate;
        PGCHANNELBINDING='require'; PGCONNECT_TIMEOUT='20'; PGCLIENTENCODING='UTF8';
        PGSERVICE=$null; PGSERVICEFILE=$null; PGOPTIONS=$null; PGHOSTADDR=$null; PGAPPNAME='roboparts-neon-copy'}
    $targetArguments = @('-X','--set=ON_ERROR_STOP=1','--no-password',('--host=' + $target.HostName),
        '--port=5432',('--username=' + $target.RoleName),'--dbname=roboparts')
    $guard = Join-Path $working 'guard.sql'
    [IO.File]::WriteAllText($guard, (Get-RoboPartsNeonEmptyGuard), [Text.UTF8Encoding]::new($false))
    $stage = 'validacao do destino (conexao, senha, PostgreSQL 18 e banco vazio)'
    Write-Host 'Verificando acesso ao Neon e se o destino esta vazio...'
    Invoke-RoboPartsPrivateProcess -File $psql -Arguments ($targetArguments + @('--single-transaction',('--file=' + $guard))) -Environment $targetEnvironment
    $stage = 'exportacao local somente para leitura'
    Write-Host 'Exportando uma copia consistente do banco local...'
    $dump = Join-Path $working 'roboparts.dump'
    $sourceSecret = Read-RoboPartsStoredSecret -Name 'backup-database'
    $sourceEnvironment = @{PGPASSWORD=(ConvertFrom-RoboPartsSecret $sourceSecret); PGSSLMODE='disable';
        PGCONNECT_TIMEOUT='15'; PGSERVICE=$null; PGSERVICEFILE=$null; PGOPTIONS=$null; PGHOSTADDR=$null; PGCLIENTENCODING='UTF8'}
    try {
        Invoke-RoboPartsPrivateProcess -File $dumpTool -Arguments @('--host=127.0.0.1','--port=5432',
            '--username=roboparts_backup','--dbname=roboparts','--schema=roboparts','--strict-names',
            '--format=custom','--no-owner','--no-acl','--no-password',('--file=' + $dump)) -Environment $sourceEnvironment
    } finally { $sourceEnvironment.Clear(); $sourceSecret.Dispose(); $sourceSecret = $null }
    $sql = Join-Path $working 'restore.sql'
    Invoke-RoboPartsPrivateProcess -File $restoreTool -Arguments @('--no-owner','--no-acl',('--file=' + $sql),$dump)
    $counts = Get-RoboPartsDumpRowCounts -SqlFile $sql
    $verification = Join-Path $working 'verify.sql'
    [IO.File]::WriteAllText($verification, (Get-RoboPartsNeonVerification $counts), [Text.UTF8Encoding]::new($false))
    $stage = 'importacao e verificacao no Neon'
    Write-Host 'Copiando para o Neon e verificando as quantidades de todas as tabelas...'
    # The repeated guard, DDL, data, constraints and count checks share ONE
    # transaction. Any failure rolls back the whole copy. Never --clean or DROP.
    Invoke-RoboPartsPrivateProcess -File $psql -Arguments ($targetArguments + @('--single-transaction',
        ('--file=' + $guard),('--file=' + $sql),('--file=' + $verification))) -Environment $targetEnvironment
    try {
        @{status='COPIED'; completedAt=[DateTime]::UtcNow.ToString('o'); hostName=$target.HostName;
          database='roboparts'; roleName=$target.RoleName; tablesVerified=$counts.Count} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $private 'neon-copy-status.json') -Encoding UTF8
    } catch { Write-Host 'A copia foi concluida, mas nao foi possivel salvar o comprovante local.' }
    Write-Host 'COPIA CONCLUIDA E VERIFICADA. O banco local continua em uso e foi preservado.'
    Write-Host 'O site ainda precisa ser configurado e publicado na Vercel.'
    Write-Host 'A chave de duas etapas ainda precisa ser configurada no servidor, caso o recurso esteja em uso.'
} catch {
    Write-Host ('A copia nao foi concluida. Etapa: ' + $stage + '.') -ForegroundColor Red
    Write-Host 'Confira a conexao direta, banco roboparts vazio, PostgreSQL 18 e senha. Detalhes privados omitidos.'
    Write-Host 'O banco local foi preservado. Se a conexao caiu durante a importacao, confira Tables no Neon antes de tentar novamente.'
    exit 1
} finally {
    $targetEnvironment.Clear()
    if ($null -ne $target) { $target.Clear() }
    if ($null -ne $connectionSecret) { $connectionSecret.Dispose() }
    if ($null -ne $sourceSecret) { $sourceSecret.Dispose() }
    if ($null -ne $working) {
        $root = [IO.Path]::GetFullPath((Get-RoboPartsPrivateDirectory)).TrimEnd('\') + '\'
        $resolved = [IO.Path]::GetFullPath($working)
        if (-not $resolved.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -or
            (Split-Path -Leaf $resolved) -notmatch '^neon-copy-[a-f0-9]{32}$') { throw 'Limpeza fora da pasta privada recusada.' }
        # Remove only the exact files created by this run, never recursive paths.
        foreach ($name in @('guard.sql','roboparts.dump','restore.sql','verify.sql','neon-root.pem')) {
            $file = Join-Path $resolved $name
            if ([IO.File]::Exists($file)) { [IO.File]::Delete($file) }
        }
        if ([IO.Directory]::Exists($resolved)) { [IO.Directory]::Delete($resolved, $false) }
    }
}

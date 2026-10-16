[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
. (Join-Path $PSScriptRoot 'NeonMigration.ps1')
$private=Get-RoboPartsPrivateDirectory
$project=Split-Path -Parent $PSScriptRoot
$jar=Join-Path $project 'backend\target\roboparts-vercel.jar'
$target=$null;$inputSecret=$null;$appSecret=$null;$keySecret=$null;$environment=@{}
$diagnosticPath=Join-Path $private 'neon-preparation-status.txt'
try {
    if(Test-Path -LiteralPath $diagnosticPath){Remove-Item -LiteralPath $diagnosticPath -Force}
    if(-not(Test-Path -LiteralPath $jar)){throw 'Gere o pacote cloud do backend antes de continuar.'}
    $settingsPath=Join-Path $private 'neon-runtime-settings.json'
    if(Test-Path -LiteralPath $settingsPath){throw 'O usuario de publicacao ja foi configurado; nao e necessario trocar sua senha.'}
    $copy=Get-Content -LiteralPath (Join-Path $private 'neon-copy-status.json') -Raw|ConvertFrom-Json
    if($copy.status-ne 'COPIED'){throw 'Conclua a copia verificada do banco primeiro.'}
    Write-Host 'RoboParts - Preparar acesso da Vercel ao Neon'
    Write-Host 'No Neon: Connect > Connection pooling desligado > Copy snippet.'
    Write-Host 'Cole a mesma conexao DIRETA usada na copia. A senha ficara oculta e nao sera salva.'
    $inputSecret=Read-Host 'Conexao direta do Neon (entrada oculta)' -AsSecureString
    $target=ConvertFrom-RoboPartsNeonConnection (ConvertFrom-RoboPartsSecret $inputSecret)
    $inputSecret.Dispose();$inputSecret=$null
    if($target.HostName-cne $copy.hostName){throw 'O projeto Neon nao corresponde ao destino da copia verificada.'}
    $local=Get-RoboPartsPrivateSettings
    $keyBytes=[IO.File]::ReadAllBytes($local.SecurityKeyFile)
    if($keyBytes.Length-ne 32){throw 'Chave de seguranca local invalida.'}
    $keySecret=ConvertTo-SecureString ([Convert]::ToBase64String($keyBytes)) -AsPlainText -Force
    [Array]::Clear($keyBytes,0,$keyBytes.Length)
    $appPassword=New-RoboPartsRandomPassword
    $appSecret=ConvertTo-SecureString $appPassword -AsPlainText -Force
    $labels=$target.HostName.Split('.')
    $labels[0]+='-pooler'
    $jdbcOptions=':5432/roboparts?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require&connectTimeout=20'
    $environment=@{DB_URL=('jdbc:postgresql://'+$target.HostName+$jdbcOptions);
        ROBOPARTS_RUNTIME_DB_URL=('jdbc:postgresql://'+($labels -join '.')+$jdbcOptions);
        DB_USERNAME=$target.RoleName;DB_PASSWORD=$target.Password;ROBOPARTS_APP_PASSWORD=$appPassword;
        ROBOPARTS_PREPARATION_DIAGNOSTIC=$diagnosticPath}
    Write-Host 'Preparando sessoes compartilhadas, limites de tentativas e usuario restrito...'
    Invoke-RoboPartsPrivateProcess -File (Get-Command java.exe).Source -Arguments @('-Dloader.main=br.com.roboparts.security.CloudDatabaseMaintenance','-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher') -Environment $environment
    Save-RoboPartsSecret -Name 'neon-app-database' -Secret $appSecret
    Save-RoboPartsSecret -Name 'neon-security-key' -Secret $keySecret
    @{database='roboparts';directHostName=$target.HostName;pooledHostName=($labels -join '.');
        roleName='roboparts_web';managerEmail=$local.ManagerEmail;mailUsername=$local.MailUsername;
        configuredAt=[DateTime]::UtcNow.ToString('o');cloudSchemaVersion=1}|
        ConvertTo-Json|Set-Content -LiteralPath $settingsPath -Encoding UTF8
    Write-Host 'NEON PREPARADO. Usuario restrito e configuracao privada salvos neste computador.'
    Write-Host 'A senha administrativa do Neon nao foi salva. O site local continua usando o banco local.'
    Write-Host 'A proxima etapa e vincular o projeto na Vercel e configurar as variaveis de producao.'
} catch {
    Write-Host 'A preparacao nao foi concluida. Confira a conexao direta, copia anterior e permissoes no Neon.' -ForegroundColor Red
    if(Test-Path -LiteralPath $diagnosticPath) {
        $diagnostic=Get-Content -LiteralPath $diagnosticPath -Raw
        if($diagnostic -cmatch '\Astage=([A-Z_]+)\ncategory=(SUCCESS|VALIDATION|TLS|NETWORK|SQL)\nsqlState=(NONE|[A-Z0-9]{5})\n\z') {
            Write-Host ('Diagnostico seguro: etapa={0}; tipo={1}; SQLState={2}.' -f $Matches[1],$Matches[2],$Matches[3])
            if($Matches[2]-eq 'TLS'){Write-Host 'Confira a data do Windows e os certificados do Java.'}
            elseif($Matches[3]-eq '28P01'){Write-Host 'A autenticacao foi recusada. Copie novamente a conexao direta no Neon.'}
            elseif($Matches[3]-eq '42501'){Write-Host 'O Neon recusou uma permissao. Use o usuario proprietario da copia.'}
        }
    }
    Write-Host 'Senhas e mensagens internas foram omitidas. O banco local nao foi alterado.'
    exit 1
} finally {
    $environment.Clear();$appPassword=$null
    if($null-ne $target){$target.Clear()}
    if($null-ne $inputSecret){$inputSecret.Dispose()}
    if($null-ne $appSecret){$appSecret.Dispose()}
    if($null-ne $keySecret){$keySecret.Dispose()}
}

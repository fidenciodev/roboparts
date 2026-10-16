[CmdletBinding()]
param([string]$PublicOrigin)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
$secrets=@()
try {
    $private=Get-RoboPartsPrivateDirectory
    $settings=Get-Content -LiteralPath (Join-Path $private 'neon-runtime-settings.json') -Raw|ConvertFrom-Json
    if($settings.roleName-ne 'roboparts_web' -or $settings.cloudSchemaVersion-ne 1 -or
        $settings.pooledHostName-notmatch '^ep-[a-z0-9-]+-pooler\.(?:[a-z0-9-]+\.)+(?:aws|azure)\.neon\.tech$') {throw 'Prepare o acesso restrito do Neon primeiro.'}
    $password=Read-RoboPartsStoredSecret -Name 'neon-app-database';$secrets+=$password
    $key=Read-RoboPartsStoredSecret -Name 'neon-security-key';$secrets+=$key
    $gmail=Read-RoboPartsStoredSecret -Name 'gmail';$secrets+=$gmail
    $values=[ordered]@{
        SPRING_PROFILES_ACTIVE='prod,vercel'; PORT='8080'; DB_USERNAME='roboparts_web';
        DB_URL=('jdbc:postgresql://'+$settings.pooledHostName+':5432/roboparts?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require&connectTimeout=20');
        DB_PASSWORD=(ConvertFrom-RoboPartsSecret $password); DB_AUTO_MIGRATE='false';
        SECURITY_ENCRYPTION_KEY=(ConvertFrom-RoboPartsSecret $key);SECURITY_MANAGERS=$settings.managerEmail;
        REGISTRATION_REQUIRE_INVITATION='true';REGISTRATION_ACCESS_CODE='';
        MAIL_HOST='smtp.gmail.com';MAIL_PORT='587';MAIL_USERNAME=$settings.mailUsername;MAIL_FROM=$settings.mailUsername;
        MAIL_PASSWORD=(ConvertFrom-RoboPartsSecret $gmail); SESSION_TIMEOUT='30m'; DEBUG='false'; TRACE='false'
    }
    if(-not [string]::IsNullOrWhiteSpace($PublicOrigin)) {
        $origin=[Uri]::new($PublicOrigin,[UriKind]::Absolute)
        if($origin.Scheme-ne 'https'-or $null-eq $origin.Host-or $origin.UserInfo-ne ''-or
            $origin.Query-ne ''-or $origin.Fragment-ne ''-or $origin.AbsolutePath-ne '/') {throw 'Informe apenas a origem HTTPS do site.'}
        $values.APP_PUBLIC_URL=$origin.GetLeftPart([UriPartial]::Authority)
    }
    $lines=foreach($name in $values.Keys) {
        $value=[string]$values[$name]
        # All values come from bounded/generated credentials and validated names.
        # Reject ambiguous dotenv syntax instead of silently changing a password.
        if($value-match '[\r\n\x00"''`]' -or $value.Contains('$')) {throw 'Valor requer configuracao por CLI em vez de arquivo dotenv.'}
        $name+'="'+$value+'"'
    }
    $destination=Join-Path $private 'vercel-production.env'
    [IO.File]::WriteAllLines($destination,[string[]]$lines,[Text.UTF8Encoding]::new($false))
    $values.Clear();$lines=$null
    Write-Host 'Arquivo privado de variaveis de producao preparado:'
    Write-Host $destination
    Write-Host 'Na Vercel, importe esse arquivo apenas no ambiente Production.'
    Write-Host 'Mantenha Automatically expose System Environment Variables habilitado.'
    Write-Host 'O arquivo contem segredos: use o seletor de arquivos da Vercel; nao abra nem envie pelo chat ou GitHub.'
} catch {
    Write-Host 'Nao foi possivel preparar as variaveis. Confira a preparacao privada do Neon e do Gmail.' -ForegroundColor Red
    exit 1
} finally {
    foreach($secret in $secrets){$secret.Dispose()}
}

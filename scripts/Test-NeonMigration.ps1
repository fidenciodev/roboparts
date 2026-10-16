[CmdletBinding()]
param([switch]$Postgres,[switch]$CloudRuntime)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
. (Join-Path $PSScriptRoot 'NeonMigration.ps1')
if($CloudRuntime -and -not $Postgres){throw 'CloudRuntime requires Postgres.'}
function Assert-Refused([scriptblock]$Action) {
    $refused = $false
    try { & $Action | Out-Null } catch { $refused = $true }
    if (-not $refused) { throw 'Expected unsafe input/operation to be refused.' }
}
$valid = 'postgresql://roboparts_owner:fixture%40secret@ep-fixture-test.c-2.sa-east-1.aws.neon.tech/roboparts?sslmode=disable&options=ignored'
$parsed = ConvertFrom-RoboPartsNeonConnection $valid
if ($parsed.Password -cne 'fixture@secret' -or $parsed.RoleName -cne 'roboparts_owner' -or
    $parsed.HostName -cne 'ep-fixture-test.c-2.sa-east-1.aws.neon.tech' -or $parsed.Count -ne 3) { throw 'Connection parsing mismatch.' }
$invalidIndex = 0
foreach ($invalid in @(
    $valid.Replace('ep-fixture-test.', 'ep-fixture-test-pooler.'),
    $valid.Replace('aws.neon.tech', 'aws.neon.tech.attacker.example'),
    $valid.Replace('ep-fixture-test.c-2.sa-east-1.aws.neon.tech', '127.0.0.1'),
    $valid.Replace('/roboparts?', '/postgres?'),
    $valid.Replace('roboparts_owner:', 'postgres:'),
    $valid.Replace('postgresql:', 'https:'),
    $valid.Replace('/roboparts?', ':1234/roboparts?'),
    $valid.Replace('fixture%40secret', ''),
    ($valid + '#fragment'), 'not a connection string'
)) {
    $invalidIndex++
    try { Assert-Refused { ConvertFrom-RoboPartsNeonConnection $invalid } }
    catch { throw ('Unsafe connection case failed: ' + $invalidIndex) }
}
Write-Output 'PASS: only direct Neon /roboparts connections with the expected owner; options never forwarded.'

$project = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $project 'backend\target'
$work = Join-Path $testRoot ('neon-migration-test-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($work) | Out-Null
$sample = Join-Path $work 'sample.sql'
$sampleSql = @'
COPY roboparts.application_metadata (id, application_name) FROM stdin;
1	RoboParts
\.
COPY roboparts.flyway_schema_history (version) FROM stdin;
7
\.
COPY roboparts.users (name) FROM stdin;
COPY roboparts.fake (name) FROM stdin;
text\nwith escaped newline
\.
'@
[IO.File]::WriteAllText($sample, $sampleSql)
$counts = Get-RoboPartsDumpRowCounts $sample
if ($counts.Count -ne 3 -or $counts.users -ne 2 -or $counts.application_metadata -ne 1) { throw 'COPY parser did not respect data boundaries.' }
[void](Get-RoboPartsNeonVerification $counts)
[IO.File]::WriteAllText($sample, $sampleSql.Replace('\.', ''))
Assert-Refused { Get-RoboPartsDumpRowCounts $sample }
Assert-Refused { Get-RoboPartsNeonVerification @{'users; DROP TABLE anything'=[long]1} }
Write-Output 'PASS: counts come from the consistent dump; data cannot become verification SQL.'
if (-not $Postgres) { return }

$bin = Join-Path $env:ProgramFiles 'PostgreSQL\18\bin'
$data = Join-Path $work 'data'
$port = 55439
$started = $false
$pgEnvironment = @{PGSSLMODE='disable'; PGSERVICE=$null; PGSERVICEFILE=$null; PGHOSTADDR=$null; PGOPTIONS=$null; PGCLIENTENCODING='UTF8'}
function Invoke-TestSql([string]$Database,[string]$Sql,[string[]]$Files=@()) {
    $arguments = @('-X','--set=ON_ERROR_STOP=1','--no-password','--host=127.0.0.1',('--port='+$port),'--username=postgres',('--dbname='+$Database),'--single-transaction')
    foreach ($file in $Files) { $arguments += '--file=' + $file }
    Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'psql.exe') -Arguments $arguments -Environment $pgEnvironment -InputText $Sql
}
try {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw 'Disposable test port occupied.' }
    Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'initdb.exe') -Arguments @('-D',$data,'-U','postgres','-A','trust','--encoding=UTF8','--locale=C','--no-sync')
    # -F is ONLY for this synthetic disposable cluster, never the real database.
    Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'pg_ctl.exe') -Arguments @('-D',$data,'-l',(Join-Path $work 'postgres.log'),'-o',('-F -p '+$port+' -h 127.0.0.1'),'-w','start')
    $started = $true
    Write-Output 'Disposable PostgreSQL started.'
    Invoke-TestSql 'postgres' '' @()
    # CREATE DATABASE cannot run in a transaction.
    Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'psql.exe') -Arguments @('-X','--set=ON_ERROR_STOP=1','--no-password','--host=127.0.0.1',('--port='+$port),'--username=postgres','--dbname=postgres') -Environment $pgEnvironment -InputText 'CREATE DATABASE roboparts;'
    Write-Output 'Disposable source database created.'
    $jar = Join-Path $testRoot 'roboparts-backend-0.1.0-SNAPSHOT.jar'
    Invoke-RoboPartsPrivateProcess -File (Get-Command java.exe).Source -Arguments @('-Dloader.main=br.com.roboparts.security.DatabaseMaintenance','-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher','migrate') -Environment @{DB_URL=('jdbc:postgresql://127.0.0.1:'+$port+'/roboparts');DB_USERNAME='postgres';DB_PASSWORD='synthetic-fixture-only'}
    Write-Output 'Fixture migrations applied.'
    Invoke-TestSql 'roboparts' "INSERT INTO roboparts.users(id,name,email,password_hash,created_at,email_verified) VALUES ('11111111-1111-4111-8111-111111111111','Synthetic fixture','fixture@example.com','test-only',now(),true);"
    $dump = Join-Path $work 'fixture.dump'
    $sql = Join-Path $work 'restore.sql'
    Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'pg_dump.exe') -Arguments @('--host=127.0.0.1',('--port='+$port),'--username=postgres','--dbname=roboparts','--schema=roboparts','--format=custom','--no-owner','--no-acl','--no-password',('--file='+$dump)) -Environment $pgEnvironment
    Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'pg_restore.exe') -Arguments @('--no-owner','--no-acl',('--file='+$sql),$dump)
    $actualCounts = Get-RoboPartsDumpRowCounts $sql
    if ($actualCounts.users -ne 1 -or $actualCounts.Count -lt 15) { throw 'Actual dump count mismatch.' }
    $guard = Join-Path $work 'guard.sql'
    $verify = Join-Path $work 'verify.sql'
    [IO.File]::WriteAllText($guard, (Get-RoboPartsNeonEmptyGuard))
    [IO.File]::WriteAllText($verify, (Get-RoboPartsNeonVerification $actualCounts))
    Assert-Refused { Invoke-TestSql 'postgres' '' @($guard) }
    Assert-Refused { Invoke-TestSql 'roboparts' '' @($guard) }
    Write-Output 'PASS: wrong database and existing installation refused without overwriting.'
    # Only fixture objects in the disposable cluster are removed between tests.
    Invoke-TestSql 'roboparts' 'DROP SCHEMA roboparts CASCADE; CREATE TABLE public.existing_data(id integer); INSERT INTO public.existing_data VALUES (123);'
    Assert-Refused { Invoke-TestSql 'roboparts' '' @($guard) }
    Invoke-TestSql 'roboparts' "DO 'BEGIN IF (SELECT id FROM public.existing_data)<>123 THEN RAISE EXCEPTION ''Lost fixture''; END IF; END'; DROP TABLE public.existing_data;"
    Invoke-TestSql 'roboparts' '' @($guard,$sql,$verify)
    Invoke-TestSql 'roboparts' "DO 'BEGIN IF (SELECT count(*) FROM roboparts.users)<>1 THEN RAISE EXCEPTION ''Count mismatch''; END IF; END';"
    Write-Output 'PASS: full PostgreSQL 18 export/import, constraints and per-table verification.'
    Invoke-TestSql 'roboparts' 'DROP SCHEMA roboparts CASCADE;'
    $actualCounts.users = [long]2
    [IO.File]::WriteAllText($verify, (Get-RoboPartsNeonVerification $actualCounts))
    Assert-Refused { Invoke-TestSql 'roboparts' '' @($guard,$sql,$verify) }
    Invoke-TestSql 'roboparts' '' @($guard)
    Write-Output 'PASS: verification failure rolls back all restored data and DDL; destination stays empty.'
    if($CloudRuntime) {
        $actualCounts.users=[long]1
        [IO.File]::WriteAllText($verify,(Get-RoboPartsNeonVerification $actualCounts))
        Invoke-TestSql 'roboparts' '' @($guard,$sql,$verify)
        Invoke-TestSql 'roboparts' @'
CREATE ROLE roboparts_owner LOGIN CREATEROLE NOCREATEDB;
ALTER DATABASE roboparts OWNER TO roboparts_owner;
ALTER SCHEMA roboparts OWNER TO roboparts_owner;
DO $fixture$ DECLARE t record; BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='roboparts' LOOP
    EXECUTE format('ALTER TABLE roboparts.%I OWNER TO roboparts_owner',t.tablename);
  END LOOP;
END $fixture$;
'@
        $cloudJar=Join-Path $testRoot 'roboparts-vercel.jar'
        $cloudPassword='synthetic-cloud-password-' + ('x'*40)
        $cloudEnvironment=@{DB_URL=('jdbc:postgresql://127.0.0.1:'+$port+'/roboparts');ROBOPARTS_RUNTIME_DB_URL=('jdbc:postgresql://127.0.0.1:'+$port+'/roboparts');DB_USERNAME='roboparts_owner';DB_PASSWORD='synthetic-owner';ROBOPARTS_APP_PASSWORD=$cloudPassword}
        $cloudArguments=@('-Dloader.main=br.com.roboparts.security.CloudDatabaseMaintenance','-cp',$cloudJar,'org.springframework.boot.loader.launch.PropertiesLauncher')
        for($run=0;$run-lt 2;$run++){
            Invoke-RoboPartsPrivateProcess -File (Get-Command java.exe).Source -Arguments $cloudArguments -Environment $cloudEnvironment
        }
        Write-Output 'PASS: cloud preparation and repeated setup work as a non-superuser database owner.'
        # SCRAM authentication is tested only on this synthetic disposable cluster.
        [IO.File]::WriteAllText((Join-Path $data 'pg_hba.conf'),"host roboparts roboparts_web 127.0.0.1/32 scram-sha-256`nhost all all 127.0.0.1/32 trust`n")
        Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'pg_ctl.exe') -Arguments @('-D',$data,'reload')
        $runtimeArguments=@('-X','--set=ON_ERROR_STOP=1','--no-password','--host=127.0.0.1',('--port='+$port),'--username=roboparts_web','--dbname=roboparts','--single-transaction')
        $runtimeEnvironment=$pgEnvironment.Clone();$runtimeEnvironment.PGPASSWORD=$cloudPassword
        Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'psql.exe') -Arguments $runtimeArguments -Environment $runtimeEnvironment -InputText 'SELECT * FROM roboparts.users; SELECT * FROM roboparts.cloud_state; SELECT * FROM roboparts.auth_attempt_locks FOR UPDATE;'
        foreach($forbidden in @('CREATE TABLE roboparts.forbidden(id integer);','UPDATE roboparts.audit_events SET action=action;','DELETE FROM roboparts.movements;','DELETE FROM roboparts.cloud_state;','INSERT INTO roboparts.auth_attempt_locks VALUES (''other'');','CREATE ROLE forbidden_cloud_role;')){
            Assert-Refused {Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'psql.exe') -Arguments $runtimeArguments -Environment $runtimeEnvironment -InputText $forbidden}
        }
        $runtimeEnvironment.PGPASSWORD='synthetic-wrong-password'
        Assert-Refused {Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'psql.exe') -Arguments $runtimeArguments -Environment $runtimeEnvironment -InputText 'SELECT 1;'}
        Write-Output 'PASS: runtime SCRAM login, wrong-password refusal and least-privilege grants.'
        $httpPort=58083
        if(Get-NetTCPConnection -LocalPort $httpPort -State Listen -ErrorAction SilentlyContinue){throw 'Disposable HTTP port occupied.'}
        $info=[Diagnostics.ProcessStartInfo]::new()
        $info.FileName=(Get-Command java.exe).Source;$info.Arguments='-jar "'+$cloudJar+'"'
        $info.WorkingDirectory=$PSScriptRoot;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
        $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        $serverEnvironment=@{SPRING_PROFILES_ACTIVE='prod,vercel';DB_URL=$cloudEnvironment.DB_URL;DB_USERNAME='roboparts_web';DB_PASSWORD=$cloudPassword;DB_AUTO_MIGRATE='false';
          PORT="$httpPort";SERVER_ADDRESS='127.0.0.1';SECURITY_ENCRYPTION_KEY=('A'*43+'=');SECURITY_MANAGERS='fixture@example.com';
          APP_PUBLIC_URL='https://roboparts.example';REGISTRATION_REQUIRE_INVITATION='true';MAIL_HOST='127.0.0.1';MAIL_USERNAME='';MAIL_PASSWORD='';DEBUG='false';TRACE='false'}
        foreach($key in $serverEnvironment.Keys){$info.EnvironmentVariables[$key]=$serverEnvironment[$key]}
        $server=$null
        try {
            $server=[Diagnostics.Process]::Start($info)
            $serverOut=$server.StandardOutput.ReadToEndAsync();$serverErrors=$server.StandardError.ReadToEndAsync()
            $ready=$false
            for($attempt=0;$attempt-lt 90;$attempt++) {
                if($server.HasExited){throw 'Cloud fixture server exited before startup.'}
                try {$health=Invoke-RestMethod -Uri ('http://127.0.0.1:'+$httpPort+'/actuator/health') -Headers @{'X-Forwarded-Proto'='https'} -TimeoutSec 2;if($health.status-eq 'UP'){$ready=$true;break}}catch{}
                Start-Sleep -Seconds 1
            }
            if(-not $ready){throw 'Cloud fixture server startup timed out.'}
            $response=Invoke-WebRequest -UseBasicParsing -Uri ('http://127.0.0.1:'+$httpPort+'/api/auth/csrf') -Headers @{'X-Forwarded-Proto'='https'} -TimeoutSec 10
            if($response.StatusCode-ne 200-or $response.Headers['Set-Cookie']-notmatch 'JSESSIONID=([^;]+);') {throw 'Cloud HTTP session was not created.'}
            $sessionId=$Matches[1]
            # PowerShell's CookieContainer can replace a raw Cookie header. This
            # fixture connects to the local HTTP side of a simulated TLS proxy,
            # so explicitly replay its cookie with a test-only non-secure copy.
            $browser=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
            $browser.Cookies.Add([Net.Cookie]::new('JSESSIONID',$sessionId,'/','127.0.0.1'))
            Invoke-WebRequest -UseBasicParsing -WebSession $browser -Uri ('http://127.0.0.1:'+$httpPort+'/api/auth/csrf') -Headers @{'X-Forwarded-Proto'='https'} -TimeoutSec 10|Out-Null
            Invoke-TestSql 'roboparts' "DO 'BEGIN IF (SELECT count(*) FROM roboparts.web_sessions)<>1 THEN RAISE EXCEPTION ''Cloud session not shared''; END IF; END';"
            Write-Output 'PASS: packaged prod,vercel server starts with restricted role; HTTPS proxy and persisted CSRF sessions work on PostgreSQL 18.'
        } finally {
            if($null-ne $server){if(-not $server.HasExited){$server.Kill();$server.WaitForExit()};$server.Dispose()}
        }
    }
} catch {
    Write-Output $_.ScriptStackTrace
    throw
} finally {
    if ($started) { Invoke-RoboPartsPrivateProcess -File (Join-Path $bin 'pg_ctl.exe') -Arguments @('-D',$data,'-m','fast','-w','stop') }
    Write-Output 'Disposable fixture files retained under ignored backend/target; no real database or credentials used.'
}

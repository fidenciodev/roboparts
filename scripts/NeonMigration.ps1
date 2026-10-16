Set-StrictMode -Version Latest

function ConvertFrom-RoboPartsNeonConnection {
    param([Parameter(Mandatory)][string]$ConnectionText)
    try {
        $uri = [Uri]::new($ConnectionText.Trim(), [UriKind]::Absolute)
        if ($uri.Scheme -notin @('postgresql','postgres') -or $uri.AbsolutePath -cne '/roboparts' -or
            $uri.Fragment -ne '' -or ($uri.Port -ne -1 -and $uri.Port -ne 5432) -or
            $uri.DnsSafeHost -notmatch '^ep-[a-z0-9-]+\.(?:[a-z0-9-]+\.)+(?:aws|azure)\.neon\.tech$' -or
            $uri.DnsSafeHost.Split('.')[0].EndsWith('-pooler')) { throw 'Invalid target.' }
        $credentials = $uri.UserInfo.Split([char[]]':', 2, [StringSplitOptions]::None)
        if ($credentials.Length -ne 2) { throw 'Missing credentials.' }
        $role = [Uri]::UnescapeDataString($credentials[0])
        $password = [Uri]::UnescapeDataString($credentials[1])
        if ($role -cne 'roboparts_owner' -or [string]::IsNullOrWhiteSpace($password) -or $password -match '[\x00-\x1f\x7f]') { throw 'Invalid credentials.' }
        # URI options are intentionally not forwarded: TLS, host, port and database
        # are fixed below, so a pasted URL cannot weaken transport or inject options.
        return @{HostName=$uri.DnsSafeHost; RoleName=$role; Password=$password}
    } catch {
        throw 'Use a connection string direta do Neon, sem pooling, do banco roboparts e usuario roboparts_owner. O endereco e a senha foram omitidos.'
    }
}

function Get-RoboPartsNeonEmptyGuard {
    return @'
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';
DO $roboparts_guard$
BEGIN
    IF current_database() <> 'roboparts' THEN
        RAISE EXCEPTION 'Wrong destination database';
    END IF;
    IF current_setting('server_version_num')::integer NOT BETWEEN 180000 AND 189999 THEN
        RAISE EXCEPTION 'PostgreSQL 18 required';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = 'roboparts') OR EXISTS (
        SELECT 1 FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
          AND c.relkind IN ('r','p','S','v','m','f')
    ) THEN
        RAISE EXCEPTION 'Destination must be empty; existing objects will not be overwritten';
    END IF;
END
$roboparts_guard$;
'@
}

function Get-RoboPartsDumpRowCounts {
    param([Parameter(Mandatory)][string]$SqlFile)
    $counts = @{}
    $table = $null
    foreach ($line in [IO.File]::ReadLines($SqlFile, [Text.Encoding]::UTF8)) {
        if ($null -ne $table) {
            if ($line -ceq '\.') { $table = $null }
            else { $counts[$table] = [long]$counts[$table] + 1 }
        } elseif ($line -cmatch '^COPY roboparts\.([a-z_]+) \(.+\) FROM stdin;$') {
            $table = $Matches[1]
            if ($counts.ContainsKey($table)) { throw 'Tabela duplicada na exportacao.' }
            $counts[$table] = [long]0
        }
    }
    if ($null -ne $table -or -not $counts.ContainsKey('users') -or
        -not $counts.ContainsKey('flyway_schema_history') -or -not $counts.ContainsKey('application_metadata')) {
        throw 'A exportacao nao identifica uma instalacao completa do RoboParts.'
    }
    return $counts
}

function Get-RoboPartsNeonVerification {
    param([Parameter(Mandatory)][hashtable]$RowCounts)
    $checks = foreach ($name in ($RowCounts.Keys | Sort-Object)) {
        if ($name -cnotmatch '^[a-z_]+$' -or $RowCounts[$name] -isnot [long] -or $RowCounts[$name] -lt 0) { throw 'Contagem de exportacao invalida.' }
        'IF (SELECT count(*) FROM roboparts.' + $name + ') <> ' + $RowCounts[$name] + " THEN RAISE EXCEPTION 'Row count mismatch'; END IF;"
    }
    return ('DO $roboparts_verify$ BEGIN ' + ($checks -join "`n") + @'

IF (SELECT count(*) FROM roboparts.application_metadata WHERE id = 1 AND application_name = 'RoboParts') <> 1
   OR EXISTS (SELECT 1 FROM roboparts.flyway_schema_history WHERE NOT success)
   OR NOT EXISTS (SELECT 1 FROM roboparts.flyway_schema_history WHERE success AND version = '7') THEN
    RAISE EXCEPTION 'RoboParts migration identity mismatch';
END IF;
END $roboparts_verify$;
'@)
}

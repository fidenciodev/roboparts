[CmdletBinding()]
param([string]$ManagerEmail,[string]$BackupDirectory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
$project=Split-Path -Parent $PSScriptRoot
$private=Get-RoboPartsPrivateDirectory
$jar=Join-Path $project 'backend\target\roboparts-backend-0.1.0-SNAPSHOT.jar'
if(-not(Test-Path -LiteralPath $jar -PathType Leaf)){throw 'Gere o backend antes de configurar a seguranca.'}
$java=(Get-Command java.exe -ErrorAction Stop).Source
$admin=$null
try {
    $preferencesPath=Join-Path $private 'setup-preferences.json'
    $preferences=if(Test-Path -LiteralPath $preferencesPath){Get-Content -LiteralPath $preferencesPath -Raw|ConvertFrom-Json}else{$null}
    if([string]::IsNullOrWhiteSpace($ManagerEmail)) {
        if($null-ne $preferences){$ManagerEmail=$preferences.ManagerEmail}else{$ManagerEmail=Read-Host 'E-mail cadastrado do responsavel por gerenciar acessos'}
    }
    $ManagerEmail=$ManagerEmail.Trim().ToLowerInvariant()
    if($ManagerEmail-notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$'){throw 'Informe um e-mail valido para o responsavel.'}
    if([string]::IsNullOrWhiteSpace($BackupDirectory)){$BackupDirectory=Join-Path $private 'Backups'}
    $BackupDirectory=[IO.Path]::GetFullPath($BackupDirectory)
    $projectRoot=[IO.Path]::GetFullPath($project).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    if(($BackupDirectory+[IO.Path]::DirectorySeparatorChar).StartsWith($projectRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Escolha um destino de backup fora do projeto.'}
    Write-Host 'Configuracao de seguranca do RoboParts. Nao envie senhas pelo chat.'
    Write-Host ('Responsavel: '+$ManagerEmail)
    Write-Host ('Backups criptografados: '+$BackupDirectory)
    $admin=Read-Host 'Senha atual do postgres para aplicar atualizacoes e criar usuarios exclusivos (entrada oculta)' -AsSecureString
    if($admin.Length-eq 0){throw 'A senha do postgres nao pode ficar vazia.'}
    $adminPassword=ConvertFrom-RoboPartsSecret $admin
    $maintenance=@{DB_USERNAME='postgres';DB_PASSWORD=$adminPassword;DB_URL='jdbc:postgresql://127.0.0.1:5432/roboparts'}
    $launcher=@('-Dloader.main=br.com.roboparts.security.DatabaseMaintenance','-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher')
    Invoke-RoboPartsPrivateProcess -File $java -Arguments ($launcher+@('migrate')) -Environment $maintenance
    Write-Host 'Atualizacoes do banco verificadas. Seus dados foram preservados.'
    $appPassword=New-RoboPartsRandomPassword;$backupPassword=New-RoboPartsRandomPassword
    $maintenance.ROBOPARTS_APP_PASSWORD=$appPassword;$maintenance.ROBOPARTS_BACKUP_PASSWORD=$backupPassword
    Invoke-RoboPartsPrivateProcess -File $java -Arguments ($launcher+@('roles')) -Environment $maintenance
    Save-RoboPartsSecret -Name 'app-database' -Secret (ConvertTo-SecureString $appPassword -AsPlainText -Force)
    Save-RoboPartsSecret -Name 'backup-database' -Secret (ConvertTo-SecureString $backupPassword -AsPlainText -Force)
    $appPassword=$null;$backupPassword=$null;$adminPassword=$null;$maintenance.Clear();$admin.Dispose();$admin=$null
    Write-Host 'Contas exclusivas do banco configuradas. A senha administrativa nao foi salva.'
    $gmailPath=Join-Path $private 'gmail.credential.xml'
    $settingsPath=Join-Path $private 'settings.json'
    if(Test-Path -LiteralPath $settingsPath){$oldSettings=Get-Content -LiteralPath $settingsPath -Raw|ConvertFrom-Json}else{$oldSettings=$null}
    if((Test-Path -LiteralPath $gmailPath)-and $null-ne $oldSettings){$gmail=$oldSettings.MailUsername}
    else {
        $gmail=(Read-Host 'Gmail que enviara os codigos').Trim().ToLowerInvariant()
        if($gmail-notmatch '^[^\s@]+@gmail\.com$'){throw 'Informe uma conta Gmail valida.'}
        $gmailSecret=Read-Host 'Nova senha de app do Gmail (16 caracteres, entrada oculta)' -AsSecureString
        $plain=(ConvertFrom-RoboPartsSecret $gmailSecret)-replace '\s',''
        if($plain.Length-ne 16){throw 'A senha de app precisa ter 16 caracteres.'}
        Save-RoboPartsSecret -Name 'gmail' -Secret (ConvertTo-SecureString $plain -AsPlainText -Force)
        $plain=$null;$gmailSecret.Dispose()
    }
    if(-not(Test-Path -LiteralPath (Join-Path $private 'registration.credential.xml'))){
        $registration=Read-Host 'Codigo inicial do responsavel (24 a 256 caracteres, entrada oculta)' -AsSecureString
        if($registration.Length-lt 24-or $registration.Length-gt 256){throw 'O codigo inicial precisa ter entre 24 e 256 caracteres.'}
        Save-RoboPartsSecret -Name 'registration' -Secret $registration;$registration.Dispose()
    }
    if(-not(Test-Path -LiteralPath (Join-Path $private 'backup-phrase.credential.xml'))){
        Write-Host 'Crie uma frase exclusiva para os backups e guarde-a fora deste computador. Ela permite restaurar em outra maquina.'
        $phrase=Read-Host 'Frase de protecao dos backups (24 a 256 caracteres, entrada oculta)' -AsSecureString
        $repeat=Read-Host 'Repita a frase de protecao dos backups' -AsSecureString
        $one=ConvertFrom-RoboPartsSecret $phrase;$two=ConvertFrom-RoboPartsSecret $repeat
        if($one.Length-lt 24-or $one.Length-gt 256-or $one-ne $two){throw 'As frases precisam ser iguais e ter entre 24 e 256 caracteres.'}
        Save-RoboPartsSecret -Name 'backup-phrase' -Secret $phrase
        $one=$null;$two=$null;$phrase.Dispose();$repeat.Dispose()
    }
    $keyFile=Join-Path $private 'security.key'
    if(-not(Test-Path -LiteralPath $keyFile)){
        $bytes=New-Object byte[] 32;$random=[Security.Cryptography.RandomNumberGenerator]::Create()
        try{$random.GetBytes($bytes);[IO.File]::WriteAllBytes($keyFile,$bytes)}finally{$random.Dispose();[Array]::Clear($bytes,0,$bytes.Length)}
    }
    if((Get-Item -LiteralPath $keyFile).Length-ne 32){throw 'A chave de seguranca existente nao tem o formato esperado. Ela nao foi substituida.'}
    @{ManagerEmail=$ManagerEmail;MailUsername=$gmail;BackupDirectory=$BackupDirectory;SecurityKeyFile=$keyFile;ProjectDirectory=$project}|ConvertTo-Json|Set-Content -LiteralPath $settingsPath -Encoding UTF8
    $global:LASTEXITCODE=0
    & (Join-Path $PSScriptRoot 'Backup-Database.ps1')
    if($LASTEXITCODE -ne 0){throw 'Confira o resultado do primeiro backup antes de continuar.'}
    $action=New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $project -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+(Join-Path $PSScriptRoot 'Backup-Database.ps1')+'"')
    $trigger=New-ScheduledTaskTrigger -Daily -At '02:00'
    $principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $taskSettings=New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName 'RoboParts-DailyBackup' -Action $action -Trigger $trigger -Principal $principal -Settings $taskSettings -Description 'Backup criptografado diario do RoboParts; executa com o usuario conectado e recupera uma execucao perdida.' -Force | Out-Null
    Write-Host 'Backup inicial verificado. Backup diario agendado para 02:00, com execucao posterior se o computador estiver indisponivel.'
    Write-Host 'Configuracao concluida. A conta responsavel precisa confirmar o e-mail antes de gerenciar acessos.'
    Write-Host 'Para usar os novos recursos, encerre o servidor antigo e abra Iniciar-RoboParts.cmd.'
} catch {Write-Error 'A configuracao nao terminou. Confira o ultimo passo exibido e os dados digitados. Senhas e detalhes privados foram omitidos.' -ErrorAction Continue;exit 1}
finally {if($null-ne $admin){$admin.Dispose()};$adminPassword=$null;$appPassword=$null;$backupPassword=$null}

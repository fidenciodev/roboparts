[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'PrivateEnvironment.ps1')
$private=Get-RoboPartsPrivateDirectory
$owner=[IO.Directory]::GetAccessControl($private,[Security.AccessControl.AccessControlSections]::Owner).GetOwner([Security.Principal.SecurityIdentifier])
$name='permissions-test-'+([guid]::NewGuid().ToString('N') -replace '[0-9]','')
$file=Join-Path $private ($name+'.credential.xml')
$secret=$null;$restored=$null
try {
    $again=Get-RoboPartsPrivateDirectory
    if($again -ne $private){throw 'Private directory changed.'}
    $acl=[IO.Directory]::GetAccessControl($private,[Security.AccessControl.AccessControlSections]::Access)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if(-not $acl.AreAccessRulesProtected -or $rules.Count -ne 1 -or $rules[0].IdentityReference -ne $sid -or $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne 'FullControl') {throw 'Private directory permissions are not restricted to the current user.'}
    $after=[IO.Directory]::GetAccessControl($private,[Security.AccessControl.AccessControlSections]::Owner).GetOwner([Security.Principal.SecurityIdentifier])
    if($owner -ne $after){throw 'Directory ownership changed.'}
    $secret=ConvertTo-SecureString 'Synthetic-test-value-not-a-real-credential' -AsPlainText -Force
    Save-RoboPartsSecret -Name $name -Secret $secret
    $restored=Read-RoboPartsStoredSecret -Name $name
    if((ConvertFrom-RoboPartsSecret $restored) -ne 'Synthetic-test-value-not-a-real-credential'){throw 'Protected credential round trip failed.'}
    $child=[IO.File]::GetAccessControl($file,[Security.AccessControl.AccessControlSections]::Access)
    $childRules=@($child.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if($childRules.Count -eq 0 -or @($childRules|Where-Object {$_.IdentityReference -ne $sid -or $_.AccessControlType -ne 'Allow'}).Count -ne 0){throw 'New credential file did not inherit private permissions.'}
    $previousDirectory=[Environment]::CurrentDirectory
    try {
        [Environment]::CurrentDirectory=Join-Path $env:windir 'System32'
        Invoke-RoboPartsPrivateProcess -File (Get-Command powershell.exe).Source -Arguments @('-NoProfile','-Command','if([Environment]::CurrentDirectory -ne $env:ROBOPARTS_TEST_DIRECTORY){exit 1}') -Environment @{ROBOPARTS_TEST_DIRECTORY=$PSScriptRoot}
    } finally {[Environment]::CurrentDirectory=$previousDirectory}
    $previousTestVariable=[Environment]::GetEnvironmentVariable('ROBOPARTS_TEST_REMOVED')
    try {
        [Environment]::SetEnvironmentVariable('ROBOPARTS_TEST_REMOVED','synthetic-fixture')
        Invoke-RoboPartsPrivateProcess -File (Get-Command powershell.exe).Source -Arguments @('-NoProfile','-Command','if(Test-Path Env:\ROBOPARTS_TEST_REMOVED){exit 1}') -Environment @{ROBOPARTS_TEST_REMOVED=$null}
    } finally {[Environment]::SetEnvironmentVariable('ROBOPARTS_TEST_REMOVED',$previousTestVariable)}
    Write-Output 'PASS: private DACL, unchanged owner, repeated setup, inherited file permissions, DPAPI round trip and process directory independent of System32.'
    Write-Output 'PASS: null overrides remove inherited process environment variables.'
} finally {
    if($null -ne $secret){$secret.Dispose()}
    if($null -ne $restored){$restored.Dispose()}
    if(Test-Path -LiteralPath $file -PathType Leaf){Remove-Item -LiteralPath $file -Force}
}

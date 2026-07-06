$ErrorActionPreference = 'Stop'

$projectDir = 'C:\dev\workspace-egov\pandora'
$logDir = Join-Path $projectDir 'runtime\batch\logs'
$logPath = Join-Path $logDir 'runtime-services-admin-start.log'

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Log {
    param([string] $Message)
    "[$(Get-Date -Format o)] $Message" | Add-Content -Path $logPath -Encoding UTF8
}

Write-Log 'starting runtime services'

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Log 'not running as administrator'
    throw 'This script must run from an elevated PowerShell session.'
}

$maria = Get-Service -Name MariaDB -ErrorAction Stop
if ($maria.Status -ne 'Running') {
    Write-Log "starting MariaDB from status=$($maria.Status)"
    Start-Service -Name MariaDB
    $maria.WaitForStatus('Running', [TimeSpan]::FromSeconds(45))
} else {
    Write-Log 'MariaDB already running'
}

$qdrantScript = Join-Path $projectDir 'scripts\start-qdrant.cmd'
Write-Log "starting Qdrant via $qdrantScript"
Start-Process -FilePath $qdrantScript -WindowStyle Hidden
Start-Sleep -Seconds 5

$batchScript = Join-Path $projectDir 'scripts\start-backend-18080-logged.ps1'
Write-Log "starting batch runner 18080 via $batchScript"
Start-Process `
    -FilePath 'powershell.exe' `
    -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $batchScript) `
    -WindowStyle Hidden

Write-Log 'runtime service start commands submitted'

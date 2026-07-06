$ErrorActionPreference = 'Stop'

$projectDir = 'C:\dev\workspace-egov\pandora'
$logDir = Join-Path $projectDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logPath = Join-Path $logDir 'batch-runner-18080-admin-restart.log'

function Write-Log {
    param([string]$Message)
    "[$(Get-Date -Format o)] $Message" | Add-Content -LiteralPath $logPath -Encoding UTF8
}

Set-Location $projectDir
Write-Log 'restart requested'

$listeners = netstat -ano | Select-String ':18080\s+.*LISTENING'
$pids = @()
foreach ($listener in $listeners) {
    $parts = $listener.Line.Trim() -split '\s+'
    $pidValue = $parts[-1]
    if ($pidValue -match '^\d+$') {
        $pids += [int]$pidValue
    }
}

$pids = $pids | Sort-Object -Unique
foreach ($pidValue in $pids) {
    Write-Log "stopping pid=$pidValue"
    Stop-Process -Id $pidValue -Force
}

Start-Sleep -Seconds 3

$script = Join-Path $projectDir 'scripts\start-backend-18080-logged.ps1'
Write-Log "starting 18080 via $script"
Start-Process -FilePath 'powershell.exe' -ArgumentList "-ExecutionPolicy Bypass -File `"$script`"" -WindowStyle Hidden

Start-Sleep -Seconds 8
$health = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:18080/api/law-data/semantic/batches/scheduler-status' -TimeoutSec 20
Write-Log "health status=$($health.StatusCode) body=$($health.Content)"
Write-Host $health.Content

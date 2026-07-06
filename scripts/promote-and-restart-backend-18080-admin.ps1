param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [int]$Port = 18080,
    [int]$HealthWaitSeconds = 60
)

$ErrorActionPreference = 'Stop'

$logDir = Join-Path $ProjectDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logPath = Join-Path $logDir 'batch-runner-18080-promote-restart.log'

function Write-Log([string]$Message) {
    $line = "[{0}] {1}" -f (Get-Date).ToString('o'), $Message
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
    Write-Host $line
}

function Get-PortPids([int]$TargetPort) {
    $lines = netstat -ano | Select-String (":$TargetPort\s+.*LISTENING")
    $pids = @()
    foreach ($line in $lines) {
        $parts = ($line.ToString() -split '\s+') | Where-Object { $_ }
        if ($parts.Count -gt 0) {
            $pidText = $parts[-1]
            if ($pidText -match '^\d+$') {
                $pids += [int]$pidText
            }
        }
    }
    $pids | Sort-Object -Unique
}

$promoteScript = Join-Path $ProjectDir 'scripts\promote-batch-runner.ps1'
$startScript = Join-Path $ProjectDir 'scripts\start-backend-18080-logged.ps1'
$healthUrl = "http://127.0.0.1:$Port/api/law-data/semantic/batches/scheduler-status"

Write-Log "promote and restart requested"

$pids = @(Get-PortPids -TargetPort $Port)
foreach ($pidValue in $pids) {
    Write-Log "stopping pid=$pidValue"
    Stop-Process -Id $pidValue -Force
}

$deadline = (Get-Date).AddSeconds(20)
while ((Get-PortPids -TargetPort $Port) -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
}
if (Get-PortPids -TargetPort $Port) {
    throw "Port $Port is still listening after stop attempt."
}

Write-Log "promoting runtime jar"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $promoteScript -ProjectDir $ProjectDir

Write-Log "starting 18080 via $startScript"
Start-Process -FilePath 'powershell.exe' `
    -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $startScript) `
    -WorkingDirectory $ProjectDir `
    -WindowStyle Hidden

$healthDeadline = (Get-Date).AddSeconds($HealthWaitSeconds)
do {
    Start-Sleep -Seconds 2
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 3
        if ($response.StatusCode -eq 200) {
            Write-Log "health status=200 body=$($response.Content)"
            exit 0
        }
    } catch {
        Write-Log "health pending: $($_.Exception.Message)"
    }
} while ((Get-Date) -lt $healthDeadline)

throw "18080 health check did not become healthy within $HealthWaitSeconds seconds."

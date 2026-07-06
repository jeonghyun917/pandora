param(
    [ValidateSet('app-dev', 'batch-runner')]
    [string]$Role = 'app-dev',
    [int]$Port = 0,
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora'
)

$ErrorActionPreference = 'Stop'

if ($Port -le 0) {
    $Port = if ($Role -eq 'batch-runner') { 18080 } else { 8080 }
}

$pidFile = Join-Path $ProjectDir "runtime\$Role\pandora-$Port.pid"
$candidatePids = @()
if (Test-Path -LiteralPath $pidFile) {
    $pidText = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($pidText) {
        $candidatePids += [int]$pidText
    }
}

$listeners = netstat -ano | Select-String (":$Port\s+.*LISTENING")
foreach ($line in $listeners) {
    $parts = ($line.Line -split '\s+') | Where-Object { $_ }
    if ($parts.Length -gt 0) {
        $candidatePids += [int]$parts[-1]
    }
}

$candidatePids = $candidatePids | Sort-Object -Unique
if (-not $candidatePids) {
    Write-Host "[pandora] no process found for $Role on port $Port"
    if (Test-Path -LiteralPath $pidFile) {
        Remove-Item -LiteralPath $pidFile -Force
    }
    exit 0
}

foreach ($pidValue in $candidatePids) {
    $process = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $pidValue -Force
        Write-Host "[pandora] stopped pid=$pidValue"
    }
}

if (Test-Path -LiteralPath $pidFile) {
    Remove-Item -LiteralPath $pidFile -Force
}

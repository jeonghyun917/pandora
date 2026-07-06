param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora'
)

$ErrorActionPreference = 'Stop'

$jar = Join-Path $ProjectDir 'target\pandora-0.0.1-SNAPSHOT.jar'
$batchJar = Join-Path $ProjectDir 'runtime\batch\pandora-batch-runner.jar'
$pidFiles = @(
    (Join-Path $ProjectDir 'runtime\app-dev\pandora-8080.pid'),
    (Join-Path $ProjectDir 'runtime\batch-runner\pandora-18080.pid')
)

function Show-FileStatus([string]$Label, [string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        $item = Get-Item -LiteralPath $Path
        Write-Host ("{0}: {1} bytes, updated {2}" -f $Label, $item.Length, $item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
    } else {
        Write-Host ("{0}: missing ({1})" -f $Label, $Path)
    }
}

Write-Host "[pandora] Runtime status"
Write-Host ""
Show-FileStatus "app jar" $jar
Show-FileStatus "batch runtime jar" $batchJar
Write-Host ""

Write-Host "[pandora] Listening ports"
$portLines = netstat -ano | Select-String ':8080|:18080|:6333'
if ($portLines) {
    $portLines | ForEach-Object { Write-Host $_.Line }
} else {
    Write-Host "No matching listeners found."
}

Write-Host ""
Write-Host "[pandora] PID files"
foreach ($pidFile in $pidFiles) {
    if (-not (Test-Path -LiteralPath $pidFile)) {
        Write-Host ("missing {0}" -f $pidFile)
        continue
    }
    $pidText = (Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    $process = $null
    if ($pidText) {
        $process = Get-Process -Id ([int]$pidText) -ErrorAction SilentlyContinue
    }
    $state = if ($process) { "running" } else { "stale" }
    Write-Host ("{0}: pid={1} {2}" -f $pidFile, $pidText, $state)
}

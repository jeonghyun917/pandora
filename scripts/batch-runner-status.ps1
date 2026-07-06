$ErrorActionPreference = 'Stop'

$projectDir = 'C:\dev\workspace-egov\pandora'
$targetJar = Join-Path $projectDir 'target\pandora-0.0.1-SNAPSHOT.jar'
$batchJar = Join-Path $projectDir 'runtime\batch\pandora-batch-runner.jar'
$metaPath = Join-Path $projectDir 'runtime\batch\pandora-batch-runner.meta.json'

function Show-Jar($label, $path) {
    if (Test-Path $path) {
        $item = Get-Item -LiteralPath $path
        Write-Host ("{0}: {1:n0} bytes, {2}" -f $label, $item.Length, $item.LastWriteTime)
    } else {
        Write-Host "${label}: missing ($path)"
    }
}

Write-Host "[pandora] Port listeners"
netstat -ano | Select-String ':(8080|18080)\s+.*LISTENING' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host ""
Write-Host "[pandora] Runtime jars"
Show-Jar 'dev target jar' $targetJar
Show-Jar 'batch runner jar' $batchJar

Write-Host ""
Write-Host "[pandora] Batch runner metadata"
if (Test-Path $metaPath) {
    Get-Content -LiteralPath $metaPath
} else {
    Write-Host "missing ($metaPath)"
}

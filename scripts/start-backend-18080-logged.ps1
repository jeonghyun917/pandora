Set-Location 'C:\dev\workspace-egov\pandora'

$projectDir = 'C:\dev\workspace-egov\pandora'
$javaExe = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe'
$batchJar = Join-Path $projectDir 'runtime\batch\pandora-batch-runner.jar'
$logDir = Join-Path $projectDir 'runtime\batch\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

"[$(Get-Date -Format o)] starting batch runner 18080" | Add-Content -Path (Join-Path $logDir 'batch-runner-18080.out.log')

if (-not (Test-Path $batchJar)) {
    "[$(Get-Date -Format o)] missing batch runner jar: $batchJar" | Add-Content -Path (Join-Path $logDir 'batch-runner-18080.err.log')
    "Run scripts\promote-batch-runner.ps1 first." | Add-Content -Path (Join-Path $logDir 'batch-runner-18080.err.log')
    exit 1
}

& $javaExe '-Dserver.port=18080' '-Dspring.batch.job.enabled=false' '-Dfile.encoding=UTF-8' -jar $batchJar "--logging.file.name=$logDir\batch-runner-18080-spring.log" *> (Join-Path $logDir 'batch-runner-18080.live.log')

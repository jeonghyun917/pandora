param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [string]$JarPath = '',
    [int]$Port = 28081,
    [string]$OutputName = '',
    [int]$CaseBatchSize = 10,
    [int]$RequestTimeoutMs = 180000,
    [int]$InterBatchSleepMs = 300,
    [int]$CaseLimit = 0,
    [string]$CaseIds = ''
)

$ErrorActionPreference = 'Stop'

if (-not $JarPath) {
    $JarPath = Join-Path $ProjectDir 'runtime\batch\pandora-batch-runner.jar'
}

if (-not $OutputName) {
    $OutputName = "rag-eval-full-temp-$Port-{0}" -f (Get-Date -Format 'yyyyMMdd-HHmmss')
}

$javaExe = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe'
$logDir = Join-Path $ProjectDir 'logs'
$runnerLog = Join-Path $logDir "backend-$Port-rag-eval.log"
$healthUrl = "http://127.0.0.1:$Port/api/law-data/semantic/batches/scheduler-status"
$evalScript = Join-Path $ProjectDir 'scripts\rag-eval-gate.js'

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Step([string]$Message) {
    $line = "[{0}] {1}" -f (Get-Date).ToString('o'), $Message
    Write-Host $line
    Add-Content -LiteralPath (Join-Path $logDir 'run-rag-eval-temp.log') -Value $line -Encoding UTF8
}

function Test-RunnerHealth {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Missing runner jar: $JarPath"
}

if (Test-RunnerHealth) {
    throw "Port $Port already has a healthy runner. Stop it or choose a different port."
}

$process = $null
$previousBaseUrl = $env:RAG_EVAL_BASE_URL
$previousOutput = $env:RAG_EVAL_OUTPUT
$previousReport = $env:RAG_EVAL_REPORT
$previousBatchSize = $env:RAG_EVAL_CASE_BATCH_SIZE
$previousTimeout = $env:RAG_EVAL_REQUEST_TIMEOUT_MS
$previousSleep = $env:RAG_EVAL_INTER_BATCH_SLEEP_MS
$previousLimit = $env:RAG_EVAL_CASE_LIMIT
$previousIds = $env:RAG_EVAL_CASE_IDS

try {
    Write-Step "starting temporary eval runner on port $Port with jar=$JarPath"
    $process = Start-Process -FilePath $javaExe `
        -ArgumentList @(
            "-Dserver.port=$Port",
            '-Dspring.batch.job.enabled=false',
            '-Dfile.encoding=UTF-8',
            '-jar',
            $JarPath,
            "--logging.file.name=$runnerLog"
        ) `
        -WorkingDirectory $ProjectDir `
        -WindowStyle Hidden `
        -PassThru

    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if (Test-RunnerHealth) {
            Write-Step "eval runner healthy on port $Port"
            break
        }
        Start-Sleep -Seconds 1
    }

    if (-not (Test-RunnerHealth)) {
        throw "Temporary eval runner on port $Port did not become healthy."
    }

    $env:RAG_EVAL_BASE_URL = "http://127.0.0.1:$Port"
    $env:RAG_EVAL_OUTPUT = Join-Path $logDir "$OutputName.json"
    $env:RAG_EVAL_REPORT = Join-Path $logDir "$OutputName.md"
    $env:RAG_EVAL_CASE_BATCH_SIZE = [string]$CaseBatchSize
    $env:RAG_EVAL_REQUEST_TIMEOUT_MS = [string]$RequestTimeoutMs
    $env:RAG_EVAL_INTER_BATCH_SLEEP_MS = [string]$InterBatchSleepMs
    if ($CaseLimit -gt 0) {
        $env:RAG_EVAL_CASE_LIMIT = [string]$CaseLimit
    } else {
        Remove-Item Env:\RAG_EVAL_CASE_LIMIT -ErrorAction SilentlyContinue
    }
    if ($CaseIds) {
        $env:RAG_EVAL_CASE_IDS = $CaseIds
    } else {
        Remove-Item Env:\RAG_EVAL_CASE_IDS -ErrorAction SilentlyContinue
    }

    Write-Step "running rag eval output=$($env:RAG_EVAL_OUTPUT)"
    & node $evalScript
} finally {
    if ($null -ne $previousBaseUrl) { $env:RAG_EVAL_BASE_URL = $previousBaseUrl } else { Remove-Item Env:\RAG_EVAL_BASE_URL -ErrorAction SilentlyContinue }
    if ($null -ne $previousOutput) { $env:RAG_EVAL_OUTPUT = $previousOutput } else { Remove-Item Env:\RAG_EVAL_OUTPUT -ErrorAction SilentlyContinue }
    if ($null -ne $previousReport) { $env:RAG_EVAL_REPORT = $previousReport } else { Remove-Item Env:\RAG_EVAL_REPORT -ErrorAction SilentlyContinue }
    if ($null -ne $previousBatchSize) { $env:RAG_EVAL_CASE_BATCH_SIZE = $previousBatchSize } else { Remove-Item Env:\RAG_EVAL_CASE_BATCH_SIZE -ErrorAction SilentlyContinue }
    if ($null -ne $previousTimeout) { $env:RAG_EVAL_REQUEST_TIMEOUT_MS = $previousTimeout } else { Remove-Item Env:\RAG_EVAL_REQUEST_TIMEOUT_MS -ErrorAction SilentlyContinue }
    if ($null -ne $previousSleep) { $env:RAG_EVAL_INTER_BATCH_SLEEP_MS = $previousSleep } else { Remove-Item Env:\RAG_EVAL_INTER_BATCH_SLEEP_MS -ErrorAction SilentlyContinue }
    if ($null -ne $previousLimit) { $env:RAG_EVAL_CASE_LIMIT = $previousLimit } else { Remove-Item Env:\RAG_EVAL_CASE_LIMIT -ErrorAction SilentlyContinue }
    if ($null -ne $previousIds) { $env:RAG_EVAL_CASE_IDS = $previousIds } else { Remove-Item Env:\RAG_EVAL_CASE_IDS -ErrorAction SilentlyContinue }
    if ($process -and -not $process.HasExited) {
        Write-Step "stopping temporary eval runner pid=$($process.Id)"
        Stop-Process -Id $process.Id -Force
    }
}

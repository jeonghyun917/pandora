$ErrorActionPreference = "Stop"

$workspace = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $workspace "logs"
$logPath = Join-Path $logDir "admrul-batch-monitor.log"
$skipFillQueuePath = Join-Path $logDir "admrul-skip-fill-queue.flag"
$lockPath = Join-Path $env:TEMP "pandora-admrul-batch-monitor.lock"
$monitorLockMinutes = 20
$waveLockMaxMinutes = 360
$mysql = "C:\Program Files\MariaDB 12.2\bin\mariadb.exe"
$database = "pandora"
$user = "pandora"
$password = $env:PANDORA_DB_PASSWORD
if (-not $password) {
    $password = "pandora"
}
$baseUrl = $env:PANDORA_BASE_URL
if (-not $baseUrl) {
    $baseUrl = "http://localhost:18080"
}
$qdrantUrl = "http://localhost:6333/collections/law_chunks"
$batchFillLimit = 100
if ($env:PANDORA_BATCH_FILL_LIMIT -and [int]::TryParse($env:PANDORA_BATCH_FILL_LIMIT, [ref] $batchFillLimit)) {
    $batchFillLimit = [Math]::Min([Math]::Max($batchFillLimit, 1), 5000)
}
$batchMaxActiveJobs = 1
if ($env:PANDORA_BATCH_MAX_ACTIVE_JOBS -and [int]::TryParse($env:PANDORA_BATCH_MAX_ACTIVE_JOBS, [ref] $batchMaxActiveJobs)) {
    $batchMaxActiveJobs = [Math]::Min([Math]::Max($batchMaxActiveJobs, 1), 2)
}

function Write-Log {
    param([string] $Message)
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz') $Message"
    Add-Content -Path $logPath -Value $line -Encoding UTF8
}

function Invoke-DbRows {
    param([string] $Sql)
    & $mysql --ssl=0 "-u$user" "-p$password" --batch --skip-column-names $database -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB query failed"
    }
}

function Invoke-Api {
    param(
        [string] $Method,
        [string] $Path,
        [int] $TimeoutSec = 120
    )
    $uri = "$baseUrl$Path"
    Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -TimeoutSec $TimeoutSec | Select-Object -ExpandProperty Content
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (Test-Path $lockPath) {
    $ageMinutes = ((Get-Date) - (Get-Item $lockPath).LastWriteTime).TotalMinutes
    $lockContent = Get-Content -Path $lockPath -Raw -ErrorAction SilentlyContinue
    if ($lockContent -like "law-parent-child-wave*" -and $ageMinutes -lt $waveLockMaxMinutes) {
        Write-Log "skip: law parent-child wave active age_min=$([Math]::Round($ageMinutes, 1))"
        exit 0
    }
    if ($ageMinutes -lt $monitorLockMinutes) {
        Write-Log "skip: previous monitor run still active"
        exit 0
    }
    Write-Log "stale monitor lock replaced age_min=$([Math]::Round($ageMinutes, 1))"
}

Set-Content -Path $lockPath -Value "monitor pid=$PID started=$((Get-Date).ToString('o'))"

try {
    Set-Location $workspace
    Write-Log "start admrul monitor"

    $springStatus = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/" -TimeoutSec 15 | Select-Object -ExpandProperty StatusCode
    Write-Log "spring status=$springStatus"

    $qdrant = Invoke-RestMethod -Uri $qdrantUrl -Method Get -TimeoutSec 15
    Write-Log "qdrant status=$($qdrant.result.status) points=$($qdrant.result.points_count)"

    $recover = Invoke-Api -Method Post -Path "/api/law-data/semantic/batches/recover-stale?target=admrul" -TimeoutSec 120
    Write-Log "recover-stale $recover"

    $poll = Invoke-Api -Method Post -Path "/api/law-data/semantic/batches/poll" -TimeoutSec 180
    Write-Log "poll $poll"

    $completedJobs = Invoke-DbRows @"
SELECT openai_batch_id
FROM semantic_batch_jobs
WHERE target = 'admrul'
  AND status = 'completed'
  AND ingested_count = 0
ORDER BY batch_job_id
"@

    foreach ($batchId in $completedJobs) {
        if ($batchId -and $batchId.Trim()) {
            Write-Log "ingest $batchId"
            try {
                $ingest = Invoke-Api -Method Post -Path "/api/law-data/semantic/batches/$batchId/ingest" -TimeoutSec 600
                Write-Log "ingest-result $ingest"
            } catch {
                Write-Log "ingest-error batch=$batchId $($_.Exception.Message)"
            }
        }
    }

    if (Test-Path $skipFillQueuePath) {
        Write-Log "fill-queue skipped by admrul-skip-fill-queue.flag"
    } else {
        $fill = Invoke-Api -Method Post -Path "/api/law-data/semantic/batches/fill-queue?target=admrul&limit=$batchFillLimit&maxActiveJobs=$batchMaxActiveJobs" -TimeoutSec 600
        Write-Log "fill-queue limit=$batchFillLimit maxActiveJobs=$batchMaxActiveJobs $fill"
    }

    $statusAges = Invoke-DbRows @"
SELECT CONCAT(
  'job=', batch_job_id,
  ',status=', status,
  ',completed=', completed_count, '/', submitted_count,
  ',failed=', failed_count,
  ',updated_age_min=', TIMESTAMPDIFF(MINUTE, updated_at, NOW()),
  ',submitted_age_min=', TIMESTAMPDIFF(MINUTE, submitted_at, NOW()),
  ',decision=',
  CASE
    WHEN status IN ('validating','in_progress') AND completed_count = 0 AND TIMESTAMPDIFF(MINUTE, submitted_at, NOW()) >= 30
      THEN 'completed_zero_over_30m_needs_cancel_retry_review'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 240
      THEN 'finalizing_over_4h_strong_abnormal_manual_cancel_review'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 60
      THEN 'finalizing_over_1h_long_running'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 30
      THEN 'finalizing_over_30m_watch'
    ELSE 'normal_watch'
  END
)
FROM semantic_batch_jobs
WHERE target = 'admrul'
  AND status IN ('validating','in_progress','finalizing','completed')
ORDER BY batch_job_id DESC
"@
    Write-Log "status-ages $($statusAges -join '; ')"

    $summary = Invoke-DbRows @"
SELECT CONCAT('active=', COUNT(*))
FROM semantic_batch_jobs
WHERE status IN ('validating','in_progress','finalizing')
UNION ALL
SELECT CONCAT(COALESCE(e.status,'NO_EMBED'), '=', COUNT(*))
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
  AND e.embedding_model = 'text-embedding-3-small'
  AND e.vector_store = 'law_chunks'
WHERE doc.target = 'admrul'
  AND c.use_yn = 'Y'
  AND doc.use_yn = 'Y'
GROUP BY COALESCE(e.status,'NO_EMBED')
"@
    Write-Log "summary $($summary -join '; ')"

    $reportScript = Join-Path $PSScriptRoot "report-admrul-batch-to-codex.ps1"
    if (Test-Path $reportScript) {
        $chatFlag = Join-Path $workspace ".codex-chat-report-admrul.enabled"
        try {
            if (Test-Path $chatFlag) {
                & $reportScript -DeliverToCodex
                Write-Log "codex chat report delivered"
            } else {
                & $reportScript
                Write-Log "codex chat report prepared only"
            }
        } catch {
            Write-Log "codex chat report error $($_.Exception.Message)"
        }
    }

    Write-Log "finish admrul monitor"
} catch {
    Write-Log "error $($_.Exception.Message)"
    throw
} finally {
    Remove-Item -Path $lockPath -Force -ErrorAction SilentlyContinue
}

param(
    [switch] $DeliverToCodex
)

$ErrorActionPreference = "Stop"

$workspace = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $workspace "logs"
$reportPath = Join-Path $logDir "admrul-batch-report-latest.md"
$deliveryLogPath = Join-Path $logDir "admrul-codex-report-delivery.log"
$lastMessagePath = Join-Path $logDir "admrul-codex-report-last-message.txt"
$promptPath = Join-Path $logDir "admrul-codex-report-prompt.txt"
$mysql = "C:\Program Files\MariaDB 12.2\bin\mariadb.exe"
$database = "pandora"
$user = "pandora"
$password = $env:PANDORA_DB_PASSWORD
if (-not $password) {
    $password = "pandora"
}
$qdrantUrl = "http://localhost:6333/collections/law_chunks"
$baseUrl = $env:PANDORA_BASE_URL
if (-not $baseUrl) {
    $baseUrl = "http://localhost:18080"
}
$codexExe = "C:\Users\kaces\AppData\Local\OpenAI\Codex\bin\958d608b5e0546a5\codex.exe"
$codexThreadId = "019e4d20-2dc1-7532-b8ba-c412faaea9c5"

function Write-DeliveryLog {
    param([string] $Message)
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz') $Message"
    Add-Content -Path $deliveryLogPath -Value $line -Encoding UTF8
}

function Invoke-DbRows {
    param([string] $Sql)
    & $mysql --ssl=0 "-u$user" "-p$password" --batch --skip-column-names $database -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB query failed"
    }
}

function Split-DbLine {
    param([string] $Line)
    return $Line -split "\|", -1
}

function Format-JobRows {
    param([string[]] $Rows)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("| Job | Status | Batch Done | Ingest/Index | Age | Note |")
    $lines.Add("|---:|---|---:|---|---|---|")
    foreach ($row in $Rows) {
        if (-not $row) { continue }
        $p = Split-DbLine $row
        if ($p.Count -lt 8) { continue }
        $jobId = $p[0]
        $status = $p[1]
        $batch = $p[2]
        $failed = $p[3]
        $ingested = $p[4]
        $updatedAge = $p[5]
        $submittedAge = $p[6]
        $decision = $p[7]
        $decisionText = switch ($decision) {
            "completed_zero_over_30m" { "completed=0 over 30m: cancel/retry review" }
            "finalizing_over_4h" { "finalizing over 4h: strong abnormal" }
            "finalizing_over_1h" { "finalizing over 1h: long-running" }
            "finalizing_over_30m" { "finalizing over 30m: watch" }
            default { "normal watch" }
        }
        $indexState = if ($status -eq "INGESTED") { "INDEXED $ingested" } else { "SUBMITTED/WAIT $ingested" }
        $note = if ([int]$failed -gt 0) { "failed $failed" } elseif ($status -eq "INGESTED") { "done" } else { $decisionText }
        $lines.Add("| $jobId | $status | $batch | $indexState | updated ${updatedAge}m / submitted ${submittedAge}m | $note |")
    }
    return ($lines -join [Environment]::NewLine)
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$now = Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"
$spring = "unknown"
$qdrantStatus = "unknown"
$qdrantPoints = "unknown"
$schedulerLastRun = "unknown"
$schedulerNextRun = "unknown"
$schedulerLastResult = "unknown"

try {
    $spring = (Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/" -TimeoutSec 10).StatusCode
} catch {
    $spring = "ERROR: $($_.Exception.Message)"
}

try {
    $qdrant = Invoke-RestMethod -Uri $qdrantUrl -Method Get -TimeoutSec 10
    $qdrantStatus = $qdrant.result.status
    $qdrantPoints = $qdrant.result.points_count
} catch {
    $qdrantStatus = "ERROR: $($_.Exception.Message)"
}

try {
    $schedulerLines = & "C:\Windows\System32\schtasks.exe" /Query /TN "PandoraAdmrulBatchMonitor" /V /FO LIST
    foreach ($line in $schedulerLines) {
        if ($line -match '^Last Run Time:\s*(.+)$') {
            $schedulerLastRun = $Matches[1].Trim()
        } elseif ($line -match '^Next Run Time:\s*(.+)$') {
            $schedulerNextRun = $Matches[1].Trim()
        } elseif ($line -match '^Last Result:\s*(.+)$') {
            $schedulerLastResult = $Matches[1].Trim()
        }
    }
} catch {
    $schedulerLastRun = "ERROR: $($_.Exception.Message)"
}

$totals = @(Invoke-DbRows @"
SELECT CONCAT(
  COUNT(DISTINCT doc.document_id), '|',
  COUNT(c.chunk_id)
)
FROM law_api_document_chunks c
JOIN law_api_documents doc ON doc.document_id = c.document_id
WHERE doc.target = 'admrul'
  AND c.use_yn = 'Y'
  AND doc.use_yn = 'Y'
"@)

$counts = @(Invoke-DbRows @"
SELECT CONCAT(COALESCE(e.status,'NO_EMBED'), '|', COUNT(*))
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
ORDER BY COALESCE(e.status,'NO_EMBED')
"@)

$activeJobs = @(Invoke-DbRows @"
SELECT COUNT(*)
FROM semantic_batch_jobs
WHERE target = 'admrul'
  AND status IN ('validating','in_progress','finalizing')
"@)

$recentJobs = @(Invoke-DbRows @"
SELECT CONCAT(
  batch_job_id, '|',
  status, '|',
  completed_count, '/', submitted_count, '|',
  failed_count, '|',
  ingested_count, '|',
  TIMESTAMPDIFF(MINUTE, updated_at, NOW()), '|',
  TIMESTAMPDIFF(MINUTE, submitted_at, NOW()), '|',
  CASE
    WHEN status IN ('validating','in_progress') AND completed_count = 0 AND TIMESTAMPDIFF(MINUTE, submitted_at, NOW()) >= 30
      THEN 'completed_zero_over_30m'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 240
      THEN 'finalizing_over_4h'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 60
      THEN 'finalizing_over_1h'
    WHEN status = 'finalizing' AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) >= 30
      THEN 'finalizing_over_30m'
    ELSE 'normal_watch'
  END
)
FROM semantic_batch_jobs
WHERE target = 'admrul'
ORDER BY CASE WHEN status = 'INGESTED' THEN 1 ELSE 0 END, batch_job_id DESC
LIMIT 10
"@)

$failureCounts = @(Invoke-DbRows @"
SELECT CONCAT(
  SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), '|',
  SUM(CASE WHEN status = 'CANCELED' THEN 1 ELSE 0 END), '|',
  SUM(CASE WHEN failed_count > 0 THEN failed_count ELSE 0 END)
)
FROM semantic_batch_jobs
WHERE target = 'admrul'
"@)

$totalParts = Split-DbLine $totals[0]
$docCount = $totalParts[0]
$chunkCount = $totalParts[1]

$statusMap = @{}
foreach ($row in $counts) {
    $p = Split-DbLine $row
    if ($p.Count -ge 2) {
        $statusMap[$p[0]] = [long]$p[1]
    }
}

$indexed = if ($statusMap.ContainsKey("INDEXED")) { $statusMap["INDEXED"] } else { 0 }
$submitted = if ($statusMap.ContainsKey("BATCH_SUBMITTED")) { $statusMap["BATCH_SUBMITTED"] } else { 0 }
$remaining = if ($statusMap.ContainsKey("NO_EMBED")) { $statusMap["NO_EMBED"] } else { 0 }
$progress = if ([long]$chunkCount -gt 0) { [math]::Round(($indexed / [double]$chunkCount) * 100, 2) } else { 0 }
$failParts = Split-DbLine $failureCounts[0]

$jobTable = Format-JobRows $recentJobs
$report = @"
**admrul semantic batch monitor**

- time: $now
- total docs/chunks: $docCount docs / $chunkCount chunks
- DB INDEXED: $indexed ($progress%)
- BATCH_SUBMITTED: $submitted
- remaining NO_EMBED: $remaining
- OpenAI active jobs: $($activeJobs[0]) / 2
- Qdrant law_chunks: $qdrantStatus, points=$qdrantPoints
- Spring ${baseUrl}: $spring
- Windows scheduler: last_run=$schedulerLastRun, next_run=$schedulerNextRun, last_result=$schedulerLastResult
- failures/cancels/request failures: FAILED jobs=$($failParts[0]), CANCELED jobs=$($failParts[1]), failed_count sum=$($failParts[2])

$jobTable
"@

Set-Content -Path $reportPath -Value $report -Encoding UTF8
Write-DeliveryLog "report written $reportPath"

if ($DeliverToCodex) {
    if (-not (Test-Path $codexExe)) {
        throw "Codex executable not found: $codexExe"
    }
    $prompt = @"
Print the following admrul semantic batch monitor report in Korean in the current conversation.
Do not use tools. Do not change any numbers.

$report
"@
    Set-Content -Path $promptPath -Value $prompt -Encoding UTF8
    Write-DeliveryLog "codex delivery start"
    Get-Content -Path $promptPath -Raw -Encoding UTF8 |
        & $codexExe exec resume $codexThreadId - --output-last-message $lastMessagePath
    if ($LASTEXITCODE -ne 0) {
        Write-DeliveryLog "codex direct delivery failed with exit code $LASTEXITCODE; fallback to --last --all"
        Get-Content -Path $promptPath -Raw -Encoding UTF8 |
            & $codexExe exec resume --last --all - --output-last-message $lastMessagePath
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Codex delivery failed with exit code $LASTEXITCODE"
    }
    Write-DeliveryLog "codex delivery finish"
}

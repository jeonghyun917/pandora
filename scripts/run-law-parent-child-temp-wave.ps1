param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [string]$JarPath = '',
    [int]$Port = 28081,
    [string[]]$Targets = @('law', 'admrul'),
    [int]$CandidatePool = 120,
    [int]$MaxDocs = 50,
    [int]$MaxProjectedChunks = 1800,
    [int]$MaxProjectedChunksPerDoc = 0,
    [int]$MinTiny = 100,
    [switch]$Apply,
    [switch]$CleanupStale,
    [switch]$SkipPreview,
    [switch]$Compact,
    [int]$PendingRecoveryAttempts = 6,
    [int]$PendingRecoveryBatchSize = 25,
    [int]$PendingRecoveryLimitDocuments = 500,
    [string]$Model = 'text-embedding-3-small',
    [string]$VectorStore = 'law_chunks'
)

$ErrorActionPreference = 'Stop'

if (-not $JarPath) {
    $JarPath = Join-Path $ProjectDir 'runtime\batch\pandora-batch-runner.jar'
}

$javaExe = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe'
$mariadbExe = 'C:\Program Files\MariaDB 12.2\bin\mariadb.exe'
$logDir = Join-Path $ProjectDir 'logs'
$runnerLog = Join-Path $logDir "backend-$Port-law-wave.log"
$healthUrl = "http://127.0.0.1:$Port/api/law-data/semantic/batches/scheduler-status"
$waveScript = Join-Path $ProjectDir 'scripts\law-parent-child-rechunk-wave.js'
$staleAuditScript = Join-Path $ProjectDir 'scripts\qdrant-stale-point-audit.js'
$qdrantCollectionUrl = 'http://127.0.0.1:6333/collections/law_chunks'
$admrulMonitorLockPath = Join-Path $env:TEMP 'pandora-admrul-batch-monitor.lock'
$admrulMonitorLockToken = "law-parent-child-wave pid=$PID started=$((Get-Date).ToString('o'))"
$waveLockMaxMinutes = 360

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Step([string]$Message) {
    $line = "[{0}] {1}" -f (Get-Date).ToString('o'), $Message
    Write-Host $line
    Add-Content -LiteralPath (Join-Path $logDir 'run-law-parent-child-temp-wave.log') -Value $line -Encoding UTF8
}

function Test-RunnerHealth {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Get-QdrantCollectionStatus {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $qdrantCollectionUrl -TimeoutSec 10
        $json = $response.Content | ConvertFrom-Json
        return [pscustomobject]@{
            Ok = $true
            Status = [string]$json.result.status
            OptimizerStatus = $json.result.optimizer_status
        }
    } catch {
        return [pscustomobject]@{
            Ok = $false
            Status = 'UNREACHABLE'
            OptimizerStatus = $_.Exception.Message
        }
    }
}

function Assert-QdrantGreenForApply {
    if (-not $Apply) {
        return
    }
    $status = Get-QdrantCollectionStatus
    if (-not $status.Ok -or $status.Status.ToLowerInvariant() -ne 'green') {
        $details = $status.OptimizerStatus | ConvertTo-Json -Compress -Depth 6
        throw "Qdrant law_chunks is not green; refusing to start apply wave. status=$($status.Status) details=$details"
    }
}

function Enter-AdmrulMonitorPause {
    if (-not $Apply) {
        return
    }
    if (Test-Path -LiteralPath $admrulMonitorLockPath) {
        $ageMinutes = ((Get-Date) - (Get-Item -LiteralPath $admrulMonitorLockPath).LastWriteTime).TotalMinutes
        $content = Get-Content -LiteralPath $admrulMonitorLockPath -Raw -ErrorAction SilentlyContinue
        if ($content -like 'law-parent-child-wave*' -and $ageMinutes -lt $waveLockMaxMinutes) {
            throw "another law parent-child wave appears active; refusing to start wave. lock=$admrulMonitorLockPath ageMinutes=$([Math]::Round($ageMinutes, 2))"
        }
        if ($ageMinutes -lt 20 -and $content -notlike 'law-parent-child-wave*') {
            throw "admrul monitor appears active; refusing to start wave. lock=$admrulMonitorLockPath ageMinutes=$([Math]::Round($ageMinutes, 2))"
        }
    }
    Set-Content -LiteralPath $admrulMonitorLockPath -Value $admrulMonitorLockToken -Encoding UTF8
    Write-Step "paused law/admrul monitor with lock=$admrulMonitorLockPath"
}

function Exit-AdmrulMonitorPause {
    if (-not (Test-Path -LiteralPath $admrulMonitorLockPath)) {
        return
    }
    $content = Get-Content -LiteralPath $admrulMonitorLockPath -Raw -ErrorAction SilentlyContinue
    if ($content -like "$admrulMonitorLockToken*") {
        Remove-Item -LiteralPath $admrulMonitorLockPath -Force -ErrorAction SilentlyContinue
        Write-Step "released admrul monitor lock=$admrulMonitorLockPath"
    }
}

function Resolve-Targets([string[]]$RawTargets) {
    $allowed = @('law', 'admrul')
    $resolved = New-Object System.Collections.Generic.List[string]

    foreach ($rawTarget in $RawTargets) {
        foreach ($part in ([string]$rawTarget).Split(',')) {
            $targetValue = $part.Trim().ToLowerInvariant()
            if (-not $targetValue) {
                continue
            }
            if ($allowed -notcontains $targetValue) {
                throw "Unsupported target: $targetValue. Allowed targets: $($allowed -join ', ')"
            }
            if (-not $resolved.Contains($targetValue)) {
                $resolved.Add($targetValue)
            }
        }
    }

    if ($resolved.Count -eq 0) {
        throw "No valid targets were provided."
    }

    return $resolved.ToArray()
}

function Escape-Sql([string]$Value) {
    return ($Value -replace "\\", "\\") -replace "'", "''"
}

function Invoke-MariaDb([string]$Sql) {
    $rows = & $mariadbExe `
        '--ssl=0' `
        '--default-character-set=utf8mb4' `
        '-uroot' `
        'pandora' `
        '--batch' `
        '--raw' `
        '--skip-column-names' `
        '-e' `
        $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB query failed with exit code $LASTEXITCODE"
    }
    return @($rows | Where-Object { $_ -and $_.Trim() })
}

function Get-PendingDocuments([string]$Target) {
    $safeTarget = Escape-Sql $Target
    $safeModel = Escape-Sql $Model
    $safeVectorStore = Escape-Sql $VectorStore
    $limit = [Math]::Max(1, $PendingRecoveryLimitDocuments)
    $sql = @"
SELECT d.document_id, COUNT(*) AS pending_chunks
FROM law_api_documents d
JOIN law_api_document_chunks c
  ON c.document_id=d.document_id
LEFT JOIN law_api_chunk_embeddings e
  ON e.chunk_id=c.chunk_id
 AND e.embedding_model='$safeModel'
 AND e.vector_store='$safeVectorStore'
 AND e.status='INDEXED'
 AND COALESCE(e.content_hash,'')=COALESCE(c.content_hash,'')
WHERE d.target='$safeTarget'
  AND d.use_yn='Y'
  AND c.use_yn='Y'
  AND e.chunk_id IS NULL
GROUP BY d.document_id
ORDER BY pending_chunks DESC, d.document_id
LIMIT $limit;
"@
    return Invoke-MariaDb $sql | ForEach-Object {
        $parts = $_ -split "`t"
        [pscustomobject]@{
            DocumentId = [long]$parts[0]
            PendingChunks = [int]$parts[1]
        }
    }
}

function Invoke-IndexBatch([string]$Target, [object[]]$Rows) {
    if (-not $Rows -or $Rows.Count -eq 0) {
        return $null
    }
    $query = "target=$([uri]::EscapeDataString($Target))&limit=50000"
    foreach ($row in $Rows) {
        $query += "&documentIds=$($row.DocumentId)"
    }
    $uri = "http://127.0.0.1:$Port/api/law-data/semantic/index-documents?$query"
    $response = Invoke-WebRequest -UseBasicParsing -Method POST -Uri $uri -TimeoutSec 900
    return $response.Content
}

function Invoke-PendingRecovery([string]$Target) {
    foreach ($attempt in 1..[Math]::Max(1, $PendingRecoveryAttempts)) {
        $pendingRows = @(Get-PendingDocuments $Target)
        $pendingChunks = ($pendingRows | Measure-Object -Property PendingChunks -Sum).Sum
        if (-not $pendingChunks) {
            $pendingChunks = 0
        }
        Write-Step "pending recovery target=$Target attempt=$attempt pendingDocs=$($pendingRows.Count) pendingChunks=$pendingChunks"
        if ($pendingChunks -eq 0) {
            return
        }

        for ($index = 0; $index -lt $pendingRows.Count; $index += [Math]::Max(1, $PendingRecoveryBatchSize)) {
            $lastIndex = [Math]::Min($index + [Math]::Max(1, $PendingRecoveryBatchSize) - 1, $pendingRows.Count - 1)
            $batch = @($pendingRows[$index..$lastIndex])
            $batchChunks = ($batch | Measure-Object -Property PendingChunks -Sum).Sum
            Write-Step "pending recovery index target=$Target docs=$($batch.Count) chunks=$batchChunks"
            $content = Invoke-IndexBatch $Target $batch
            if ($content) {
                Write-Host $content
            }
        }

        Start-Sleep -Seconds 2
    }

    $remainingRows = @(Get-PendingDocuments $Target)
    $remainingChunks = ($remainingRows | Measure-Object -Property PendingChunks -Sum).Sum
    if (-not $remainingChunks) {
        $remainingChunks = 0
    }
    if ($remainingChunks -gt 0) {
        throw "Pending recovery did not finish for target=$Target. remainingDocs=$($remainingRows.Count) remainingChunks=$remainingChunks"
    }
}

function Get-LatestWaveStatus {
    $waveReport = Join-Path $logDir 'law-parent-child-rechunk-wave-latest.json'
    if (-not (Test-Path -LiteralPath $waveReport)) {
        return $null
    }

    try {
        $json = Get-Content -LiteralPath $waveReport -Encoding UTF8 -Raw | ConvertFrom-Json
        return [string]$json.status
    } catch {
        Write-Step "could not parse latest wave report: $($_.Exception.Message)"
        return $null
    }
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Missing runner jar: $JarPath"
}

$resolvedTargets = Resolve-Targets $Targets

Assert-QdrantGreenForApply
Enter-AdmrulMonitorPause

if (Test-RunnerHealth) {
    throw "Port $Port already has a healthy runner. Stop it or choose a different port."
}

$process = $null
$previousRunnerUrl = $env:PANDORA_BATCH_RUNNER_URL

try {
    Write-Step "starting temporary runner on port $Port with jar=$JarPath"
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
            Write-Step "runner healthy on port $Port"
            break
        }
        Start-Sleep -Seconds 1
    }

    if (-not (Test-RunnerHealth)) {
        throw "Temporary runner on port $Port did not become healthy."
    }

    $env:PANDORA_BATCH_RUNNER_URL = "http://127.0.0.1:$Port"
    $compactArg = if ($Compact) { '--compact=true' } else { '--compact=false' }
    $maxProjectedChunksPerDocArg = "--max-projected-chunks-per-doc=$MaxProjectedChunksPerDoc"

    foreach ($targetValue in $resolvedTargets) {
        if (-not $SkipPreview) {
            Write-Step "preview wave target=$targetValue"
            & node $waveScript `
                "--target=$targetValue" `
                '--candidate=tiny' `
                "--candidate-pool=$CandidatePool" `
                "--max-docs=$MaxDocs" `
                "--max-projected-chunks=$MaxProjectedChunks" `
                $maxProjectedChunksPerDocArg `
                "--min-tiny=$MinTiny" `
                '--index=none' `
                $compactArg
            if ($LASTEXITCODE -ne 0) {
                throw "Preview wave failed target=$targetValue exitCode=$LASTEXITCODE"
            }
        }

        if ($Apply) {
            Write-Step "apply wave target=$targetValue"
            & node $waveScript `
                "--target=$targetValue" `
                '--candidate=tiny' `
                "--candidate-pool=$CandidatePool" `
                "--max-docs=$MaxDocs" `
                "--max-projected-chunks=$MaxProjectedChunks" `
                $maxProjectedChunksPerDocArg `
                "--min-tiny=$MinTiny" `
                '--index=direct' `
                '--apply=true' `
                $compactArg
            $applyExitCode = $LASTEXITCODE
            $applyStatus = Get-LatestWaveStatus
            if ($applyExitCode -ne 0 -and $applyStatus -like 'BLOCKED*') {
                throw "Apply wave blocked before data changes target=$targetValue exitCode=$applyExitCode status=$applyStatus"
            }
            if ($applyExitCode -ne 0) {
                Write-Step "apply wave returned exitCode=$applyExitCode status=$applyStatus; running recovery before final decision"
            }

            Invoke-PendingRecovery $targetValue

            if ($CleanupStale) {
                Write-Step "cleanup qdrant stale points target=$targetValue"
                & node $staleAuditScript `
                    '--collection=law_chunks' `
                    "--target=$targetValue" `
                    '--limit=20000' `
                    '--delete=true'
                if ($LASTEXITCODE -ne 0) {
                    throw "Qdrant stale cleanup failed target=$targetValue exitCode=$LASTEXITCODE"
                }
            }

            if ($applyExitCode -ne 0 -and -not ($applyStatus -like 'APPLIED*')) {
                throw "Apply wave failed target=$targetValue exitCode=$applyExitCode status=$applyStatus"
            }
        }
    }
} finally {
    $env:PANDORA_BATCH_RUNNER_URL = $previousRunnerUrl
    if ($process -and -not $process.HasExited) {
        Write-Step "stopping temporary runner pid=$($process.Id)"
        Stop-Process -Id $process.Id -Force
    }
    Exit-AdmrulMonitorPause
}

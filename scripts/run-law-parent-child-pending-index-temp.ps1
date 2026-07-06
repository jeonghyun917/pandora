param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [string]$JarPath = '',
    [int]$Port = 28081,
    [string[]]$Targets = @('law', 'admrul'),
    [int]$LimitDocuments = 500,
    [int]$BatchSize = 25,
    [int]$Attempts = 3,
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
$runnerLog = Join-Path $logDir "backend-$Port-law-pending-index.log"
$healthUrl = "http://127.0.0.1:$Port/api/law-data/semantic/batches/scheduler-status"
$baseUrl = "http://127.0.0.1:$Port"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Step([string]$Message) {
    $line = "[{0}] {1}" -f (Get-Date).ToString('o'), $Message
    Write-Host $line
    Add-Content -LiteralPath (Join-Path $logDir 'run-law-parent-child-pending-index-temp.log') -Value $line -Encoding UTF8
}

function Test-RunnerHealth {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
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
    $limit = [Math]::Max(1, $LimitDocuments)
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
    $uri = "$baseUrl/api/law-data/semantic/index-documents?$query"
    $response = Invoke-WebRequest -UseBasicParsing -Method POST -Uri $uri -TimeoutSec 900
    return $response.Content
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Missing runner jar: $JarPath"
}

if (Test-RunnerHealth) {
    throw "Port $Port already has a healthy runner. Stop it or choose a different port."
}

$resolvedTargets = Resolve-Targets $Targets
$process = $null

try {
    Write-Step "starting temporary pending-index runner on port $Port with jar=$JarPath"
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

    foreach ($attempt in 1..[Math]::Max(1, $Attempts)) {
        $attemptIndexed = 0
        $attemptPending = 0
        Write-Step "pending index attempt=$attempt"

        foreach ($target in $resolvedTargets) {
            $pendingRows = @(Get-PendingDocuments $target)
            $pendingChunks = ($pendingRows | Measure-Object -Property PendingChunks -Sum).Sum
            if (-not $pendingChunks) {
                $pendingChunks = 0
            }
            Write-Step "target=$target pendingDocs=$($pendingRows.Count) pendingChunks=$pendingChunks"
            $attemptPending += [int]$pendingChunks

            for ($index = 0; $index -lt $pendingRows.Count; $index += [Math]::Max(1, $BatchSize)) {
                $batch = @($pendingRows[$index..([Math]::Min($index + [Math]::Max(1, $BatchSize) - 1, $pendingRows.Count - 1))])
                $batchChunks = ($batch | Measure-Object -Property PendingChunks -Sum).Sum
                Write-Step "index target=$target docs=$($batch.Count) chunks=$batchChunks"
                $content = Invoke-IndexBatch $target $batch
                if ($content) {
                    Write-Host $content
                }
                $attemptIndexed += [int]$batchChunks
            }
        }

        Write-Step "attempt=$attempt attemptedChunks=$attemptIndexed initialPendingChunks=$attemptPending"
        if ($attemptPending -eq 0) {
            break
        }

        Start-Sleep -Seconds 2
    }

    $remainingTotal = 0
    foreach ($target in $resolvedTargets) {
        $remainingRows = @(Get-PendingDocuments $target)
        $remainingChunks = ($remainingRows | Measure-Object -Property PendingChunks -Sum).Sum
        if (-not $remainingChunks) {
            $remainingChunks = 0
        }
        Write-Step "final pending target=$target pendingDocs=$($remainingRows.Count) pendingChunks=$remainingChunks"
        $remainingTotal += [int]$remainingChunks
    }

    if ($remainingTotal -gt 0) {
        throw "Pending index did not finish. remainingChunks=$remainingTotal"
    }
} finally {
    if ($process -and -not $process.HasExited) {
        Write-Step "stopping temporary pending-index runner pid=$($process.Id)"
        Stop-Process -Id $process.Id -Force
    }
}

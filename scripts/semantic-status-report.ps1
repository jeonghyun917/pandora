$ErrorActionPreference = "Stop"

$workspace = Split-Path -Parent $PSScriptRoot
$mysql = "C:\Program Files\MariaDB 12.2\bin\mariadb.exe"
$database = "pandora"
$user = "pandora"
$password = "pandora"
$qdrantCollectionUrl = "http://localhost:6333/collections/law_chunks"

function Invoke-ScalarQuery {
    param([string] $Sql)

    $result = & $mysql --ssl=0 "-u$user" "-p$password" --batch --skip-column-names $database -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB query failed: $Sql"
    }
    if ($null -eq $result -or $result.Count -eq 0) {
        return 0
    }
    return [long]($result | Select-Object -First 1)
}

function Invoke-TableQuery {
    param([string] $Sql)

    & $mysql --ssl=0 "-u$user" "-p$password" --table $database -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB query failed: $Sql"
    }
}

$dbIndexedRaw = Invoke-ScalarQuery @"
SELECT SUM(cnt)
FROM (
  SELECT COUNT(*) AS cnt
  FROM law_api_chunk_embeddings
  WHERE status = 'INDEXED'
  UNION ALL
  SELECT COUNT(*) AS cnt
  FROM rag_chunk_embeddings
  WHERE status = 'INDEXED'
) indexed
"@

$dbIndexedActive = Invoke-ScalarQuery @"
SELECT COUNT(*)
FROM (
  SELECT e.chunk_id
  FROM law_api_chunk_embeddings e
  JOIN law_api_document_chunks c ON c.chunk_id = e.chunk_id
  JOIN law_api_documents d ON d.document_id = c.document_id
  WHERE e.status = 'INDEXED'
    AND c.use_yn = 'Y'
    AND d.use_yn = 'Y'
  UNION ALL
  SELECT e.chunk_id
  FROM rag_chunk_embeddings e
  JOIN rag_document_chunks c ON c.chunk_id = e.chunk_id
  JOIN rag_documents d ON d.document_id = c.document_id
  WHERE e.status = 'INDEXED'
    AND c.use_yn = 'Y'
    AND d.use_yn = 'Y'
) indexed
"@

$remainingCandidates = Invoke-ScalarQuery @"
SELECT SUM(cnt)
FROM (
  SELECT COUNT(*) AS cnt
  FROM law_api_document_chunks c
  JOIN law_api_documents d ON d.document_id = c.document_id
  LEFT JOIN law_api_chunk_embeddings e
    ON e.chunk_id = c.chunk_id
    AND e.embedding_model = 'text-embedding-3-small'
    AND e.vector_store = 'law_chunks'
  WHERE c.use_yn = 'Y'
    AND d.use_yn = 'Y'
    AND (
      e.chunk_id IS NULL
      OR e.status IN ('FAILED', 'ERROR')
      OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
    )
  UNION ALL
  SELECT COUNT(*) AS cnt
  FROM rag_document_chunks c
  JOIN rag_documents d ON d.document_id = c.document_id
  LEFT JOIN rag_chunk_embeddings e
    ON e.chunk_id = c.chunk_id
    AND e.embedding_model = 'text-embedding-3-small'
    AND e.vector_store = 'law_chunks'
  WHERE c.use_yn = 'Y'
    AND d.use_yn = 'Y'
    AND (
      e.chunk_id IS NULL
      OR e.status IN ('FAILED', 'ERROR')
      OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
    )
) remaining
"@

$failedOrCancelledJobs = Invoke-ScalarQuery @"
SELECT COUNT(*)
FROM semantic_batch_jobs
WHERE status IN ('FAILED', 'failed', 'CANCELLED', 'cancelled', 'EXPIRED', 'expired')
"@

$retryNeeded = Invoke-ScalarQuery @"
SELECT SUM(cnt)
FROM (
  SELECT COUNT(*) AS cnt
  FROM law_api_chunk_embeddings
  WHERE status IN ('FAILED', 'ERROR')
  UNION ALL
  SELECT COUNT(*) AS cnt
  FROM rag_chunk_embeddings
  WHERE status IN ('FAILED', 'ERROR')
) failed_chunks
"@

$qdrant = Invoke-RestMethod -Uri $qdrantCollectionUrl -Method Get
$qdrantPoints = [long]$qdrant.result.points_count
$rawDelta = $qdrantPoints - $dbIndexedRaw
$activeDelta = $qdrantPoints - $dbIndexedActive

Write-Host "semantic status report"
Write-Host "workspace: $workspace"
Write-Host "time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
Write-Host ""
Write-Host "DB INDEXED chunks(raw):    $dbIndexedRaw"
Write-Host "DB INDEXED chunks(active): $dbIndexedActive"
Write-Host "Qdrant points:             $qdrantPoints"
Write-Host "Qdrant - raw DB delta:     $rawDelta"
Write-Host "Qdrant - active DB delta:  $activeDelta"
Write-Host "remaining candidates: $remainingCandidates"
Write-Host "failed/cancelled jobs: $failedOrCancelledJobs"
Write-Host "retry-needed failed chunks: $retryNeeded"
Write-Host ""

Invoke-TableQuery @"
SELECT
  d.target,
  COALESCE(e.status, 'NO_EMBED') AS status,
  COUNT(*) AS chunks
FROM law_api_document_chunks c
JOIN law_api_documents d ON d.document_id = c.document_id
LEFT JOIN law_api_chunk_embeddings e ON e.chunk_id = c.chunk_id
WHERE c.use_yn = 'Y'
  AND d.use_yn = 'Y'
GROUP BY d.target, COALESCE(e.status, 'NO_EMBED')
ORDER BY d.target, status
"@

Invoke-TableQuery @"
SELECT
  d.document_type,
  COALESCE(e.status, 'NO_EMBED') AS status,
  COUNT(*) AS chunks
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id = c.document_id
LEFT JOIN rag_chunk_embeddings e ON e.chunk_id = c.chunk_id
WHERE c.use_yn = 'Y'
  AND d.use_yn = 'Y'
GROUP BY d.document_type, COALESCE(e.status, 'NO_EMBED')
ORDER BY d.document_type, status
"@

Invoke-TableQuery @"
SELECT
  batch_job_id,
  target,
  status,
  submitted_count,
  completed_count,
  failed_count,
  ingested_count,
  updated_at
FROM semantic_batch_jobs
ORDER BY
  CASE WHEN status = 'INGESTED' THEN 1 ELSE 0 END,
  batch_job_id DESC
LIMIT 10
"@

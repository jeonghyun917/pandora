param(
  [int]$Limit = 5000,
  [int]$BatchSize = 25,
  [string]$DocumentType = "official_doc",
  [string]$Model = "text-embedding-3-small",
  [string]$VectorStore = "rag_chunks_v4",
  [string]$QdrantBaseUrl = "http://localhost:6333",
  [string]$MariaDbExe = "C:\Program Files\MariaDB 12.2\bin\mariadb.exe",
  [string]$MariaDbUser = "pandora",
  [string]$MariaDbPassword = "pandora",
  [string]$MariaDbDatabase = "pandora",
  [int]$MaxRetries = 4,
  [int]$RetrySleepSeconds = 8
)

$ErrorActionPreference = "Stop"

$PointOffset = [Int64]9000000000000000
$LogPath = Join-Path (Get-Location) "logs\direct-rag-embedding-index.log"
$ReportPath = Join-Path (Get-Location) "logs\direct-rag-embedding-index-latest.md"

function Write-Log {
  param([string]$Message)
  $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $Message"
  Write-Host $line
  Add-Content -LiteralPath $LogPath -Encoding UTF8 -Value $line
}

function Sql-Escape {
  param([AllowNull()][string]$Value)
  if ($null -eq $Value) {
    return ""
  }
  return $Value.Replace("\", "\\").Replace("'", "''")
}

function Invoke-MariaDbJsonRows {
  param([string]$Sql)
  $rows = & $MariaDbExe --ssl=0 "-u$MariaDbUser" "-p$MariaDbPassword" --batch --raw --skip-column-names $MariaDbDatabase -e $Sql
  if ($LASTEXITCODE -ne 0) {
    throw "MariaDB command failed with exit code $LASTEXITCODE"
  }
  $parsed = New-Object System.Collections.Generic.List[object]
  foreach ($row in $rows) {
    if ([string]::IsNullOrWhiteSpace($row)) {
      continue
    }
    $parsed.Add(($row | ConvertFrom-Json))
  }
  return $parsed
}

function Invoke-MariaDb {
  param([string]$Sql)
  $null = & $MariaDbExe --ssl=0 "-u$MariaDbUser" "-p$MariaDbPassword" --batch --raw $MariaDbDatabase -e $Sql
  if ($LASTEXITCODE -ne 0) {
    throw "MariaDB command failed with exit code $LASTEXITCODE"
  }
}

function Get-Candidates {
  param([int]$Count)
  $safeDocumentType = Sql-Escape $DocumentType
  $safeModel = Sql-Escape $Model
  $safeVectorStore = Sql-Escape $VectorStore
  $sql = @"
SELECT JSON_OBJECT(
  'chunkId', c.chunk_id,
  'documentId', c.document_id,
  'target', doc.document_type,
  'title', doc.title,
  'sourceOrg', COALESCE(doc.source_org, ''),
  'sourceDate', doc.published_date,
  'effectiveStatus', '',
  'chunkNo', c.chunk_no,
  'chunkTitle', c.chunk_title,
  'chunkText', COALESCE(c.embedding_text, c.chunk_text),
  'chunkVersion', c.chunk_version,
  'sourcePath', c.source_path,
  'contentHash', c.content_hash
) AS row_json
FROM rag_document_chunks c
JOIN rag_documents doc ON doc.document_id = c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
  AND e.embedding_model = '$safeModel'
  AND e.vector_store = '$safeVectorStore'
WHERE doc.use_yn = 'Y'
  AND c.use_yn = 'Y'
  AND doc.document_type = '$safeDocumentType'
  AND c.chunk_version = (
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id = c.document_id
      AND c2.use_yn = 'Y'
  )
  AND (
    e.chunk_id IS NULL
    OR e.status IN ('FAILED', 'ERROR')
    OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
  )
ORDER BY
  CASE
    WHEN e.status = 'FAILED' THEN 0
    WHEN e.status = 'ERROR' THEN 1
    WHEN e.chunk_id IS NULL THEN 2
    ELSE 3
  END,
  c.chunk_id
LIMIT $Count;
"@
  return Invoke-MariaDbJsonRows $sql
}

function Get-StatusSummary {
  $safeDocumentType = Sql-Escape $DocumentType
  $safeModel = Sql-Escape $Model
  $safeVectorStore = Sql-Escape $VectorStore
  $sql = @"
SELECT JSON_OBJECT('status', COALESCE(e.status,'MISSING_ROW'), 'cnt', COUNT(*)) AS row_json
FROM rag_document_chunks c
JOIN rag_documents d ON d.document_id = c.document_id
LEFT JOIN rag_chunk_embeddings e
  ON e.chunk_id = c.chunk_id
  AND e.embedding_model = '$safeModel'
  AND e.vector_store = '$safeVectorStore'
WHERE d.use_yn = 'Y'
  AND c.use_yn = 'Y'
  AND d.document_type = '$safeDocumentType'
  AND c.chunk_version = (
    SELECT MAX(c2.chunk_version)
    FROM rag_document_chunks c2
    WHERE c2.document_id = c.document_id
      AND c2.use_yn = 'Y'
  )
GROUP BY COALESCE(e.status,'MISSING_ROW')
ORDER BY status;
"@
  return Invoke-MariaDbJsonRows $sql
}

function Build-EmbeddingInput {
  param([object]$Chunk)
  return @(
    [string]$Chunk.title,
    [string]$Chunk.chunkNo,
    [string]$Chunk.chunkTitle,
    [string]$Chunk.chunkText
  ) -join "`n"
}

function Invoke-OpenAiEmbeddings {
  param([string[]]$Inputs)
  if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    throw "OPENAI_API_KEY is not set."
  }

  $body = @{
    model = $Model
    input = $Inputs
  } | ConvertTo-Json -Depth 6 -Compress

  for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
    try {
      $response = Invoke-WebRequest `
        -UseBasicParsing `
        -Method Post `
        -Uri "https://api.openai.com/v1/embeddings" `
        -Headers @{ Authorization = "Bearer $env:OPENAI_API_KEY" } `
        -ContentType "application/json; charset=utf-8" `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
        -TimeoutSec 180
      $json = $response.Content | ConvertFrom-Json
      $data = @($json.data | Sort-Object index)
      $embeddings = @()
      foreach ($item in $data) {
        $embeddings += ,@($item.embedding)
      }
      return $embeddings
    } catch {
      if ($attempt -ge $MaxRetries) {
        throw
      }
      $sleep = $RetrySleepSeconds * $attempt
      Write-Log "OpenAI embeddings retry attempt=$attempt sleep=${sleep}s error=$($_.Exception.Message)"
      Start-Sleep -Seconds $sleep
    }
  }
}

function Upsert-Qdrant {
  param(
    [object[]]$Chunks,
    [object[]]$Vectors
  )
  $points = New-Object System.Collections.Generic.List[object]
  for ($i = 0; $i -lt $Chunks.Count; $i++) {
    $chunk = $Chunks[$i]
    $chunkId = [Int64]$chunk.chunkId
    $pointId = $PointOffset + $chunkId
    $points.Add(@{
      id = $pointId
      vector = $Vectors[$i]
      payload = @{
        chunkId = $chunkId
        documentId = [Int64]$chunk.documentId
        target = [string]$chunk.target
        title = [string]$chunk.title
        sourceOrg = if ($null -eq $chunk.sourceOrg) { "" } else { [string]$chunk.sourceOrg }
        agencyName = if ($null -eq $chunk.sourceOrg) { "" } else { [string]$chunk.sourceOrg }
        sourceDate = if ($null -eq $chunk.sourceDate) { "" } else { [string]$chunk.sourceDate }
        effectiveStatus = ""
        chunkNo = if ($null -eq $chunk.chunkNo) { "" } else { [string]$chunk.chunkNo }
        chunkVersion = [int]$chunk.chunkVersion
        sourcePath = if ($null -eq $chunk.sourcePath) { "" } else { [string]$chunk.sourcePath }
      }
    })
  }

  $body = @{ points = $points } | ConvertTo-Json -Depth 12 -Compress
  $response = Invoke-WebRequest `
    -UseBasicParsing `
    -Method Put `
    -Uri "$QdrantBaseUrl/collections/$VectorStore/points?wait=true" `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
    -TimeoutSec 180
  if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
    throw "Qdrant upsert failed with HTTP $($response.StatusCode)"
  }
}

function Mark-Indexed {
  param([object[]]$Chunks)
  if ($Chunks.Count -eq 0) {
    return
  }
  $safeModel = Sql-Escape $Model
  $safeVectorStore = Sql-Escape $VectorStore
  $values = New-Object System.Collections.Generic.List[string]
  foreach ($chunk in $Chunks) {
    $chunkId = [Int64]$chunk.chunkId
    $pointId = [string]($PointOffset + $chunkId)
    $contentHash = Sql-Escape ([string]$chunk.contentHash)
    $values.Add("($chunkId, '$safeModel', '$safeVectorStore', '$pointId', '$contentHash', SHA2(CONCAT(CAST($chunkId AS CHAR), ':', '$contentHash'), 256), 'INDEXED', NOW(), NULL)")
  }
  $sql = @"
INSERT INTO rag_chunk_embeddings (
  chunk_id, embedding_model, vector_store, vector_point_id, content_hash, revision_hash,
  status, embedded_at, last_error_message
)
VALUES
  $($values -join ",`n  ")
ON DUPLICATE KEY UPDATE
  vector_point_id = VALUES(vector_point_id),
  content_hash = VALUES(content_hash),
  revision_hash = VALUES(revision_hash),
  status = VALUES(status),
  embedded_at = VALUES(embedded_at),
  last_error_message = VALUES(last_error_message),
  updated_at = NOW();
"@
  Invoke-MariaDb $sql
}

function Mark-FullyIndexedDocuments {
  $safeDocumentType = Sql-Escape $DocumentType
  $safeModel = Sql-Escape $Model
  $safeVectorStore = Sql-Escape $VectorStore
  $sql = @"
UPDATE rag_documents doc
SET import_status = 'INDEXED',
    last_error_message = NULL,
    imported_at = NOW(),
    updated_at = NOW()
WHERE doc.document_type = '$safeDocumentType'
  AND doc.use_yn = 'Y'
  AND NOT EXISTS (
    SELECT 1
    FROM rag_document_chunks c
    LEFT JOIN rag_chunk_embeddings e
      ON e.chunk_id = c.chunk_id
      AND e.embedding_model = '$safeModel'
      AND e.vector_store = '$safeVectorStore'
    WHERE c.document_id = doc.document_id
      AND c.use_yn = 'Y'
      AND c.chunk_version = (
        SELECT MAX(c2.chunk_version)
        FROM rag_document_chunks c2
        WHERE c2.document_id = c.document_id
          AND c2.use_yn = 'Y'
      )
      AND (
        e.chunk_id IS NULL
        OR e.status != 'INDEXED'
        OR COALESCE(e.content_hash, '') != COALESCE(c.content_hash, '')
      )
  );
"@
  Invoke-MariaDb $sql
}

if ($BatchSize -lt 1) {
  throw "BatchSize must be positive."
}

New-Item -ItemType Directory -Force -Path (Split-Path $LogPath) | Out-Null
Write-Log "direct-index start documentType=$DocumentType limit=$Limit batchSize=$BatchSize model=$Model vectorStore=$VectorStore"

$attempted = 0
$indexed = 0
$failed = 0
$startedAt = Get-Date

while ($attempted -lt $Limit) {
  $remaining = $Limit - $attempted
  $take = [Math]::Min($BatchSize, $remaining)
  $chunks = @(Get-Candidates -Count $take)
  if ($chunks.Count -eq 0) {
    Write-Log "no more candidates"
    break
  }

  $chunkIds = ($chunks | ForEach-Object { $_.chunkId }) -join ","
  try {
    $inputs = @($chunks | ForEach-Object { (Build-EmbeddingInput $_).Trim() })
    $vectors = @(Invoke-OpenAiEmbeddings -Inputs $inputs)
    if ($vectors.Count -ne $chunks.Count) {
      throw "Embedding count mismatch chunks=$($chunks.Count) vectors=$($vectors.Count)"
    }
    Upsert-Qdrant -Chunks $chunks -Vectors $vectors
    Mark-Indexed -Chunks $chunks
    $attempted += $chunks.Count
    $indexed += $chunks.Count
    Write-Log "indexed batch count=$($chunks.Count) attempted=$attempted indexed=$indexed chunkIds=$chunkIds"
  } catch {
    $attempted += $chunks.Count
    $failed += $chunks.Count
    Write-Log "failed batch count=$($chunks.Count) chunkIds=$chunkIds error=$($_.Exception.Message)"
    if ($BatchSize -gt 1 -and $chunks.Count -gt 1) {
      Write-Log "retrying individually chunkIds=$chunkIds"
      foreach ($chunk in $chunks) {
        try {
          $input = @((Build-EmbeddingInput $chunk).Trim())
          $vector = @(Invoke-OpenAiEmbeddings -Inputs $input)
          Upsert-Qdrant -Chunks @($chunk) -Vectors $vector
          Mark-Indexed -Chunks @($chunk)
          $indexed += 1
          Write-Log "indexed single attempted=$attempted indexed=$indexed chunkId=$($chunk.chunkId)"
        } catch {
          Write-Log "failed single chunkId=$($chunk.chunkId) error=$($_.Exception.Message)"
        }
      }
    }
  }
}

Mark-FullyIndexedDocuments
$summary = @(Get-StatusSummary)
$elapsed = [int]((Get-Date) - $startedAt).TotalSeconds

$summaryLines = $summary | ForEach-Object { "| $($_.status) | $($_.cnt) |" }
$report = @"
# Direct RAG Embedding Index Report

- generated_at: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
- document_type: $DocumentType
- vector_store: $VectorStore
- model: $Model
- requested_limit: $Limit
- batch_size: $BatchSize
- attempted: $attempted
- indexed: $indexed
- failed_batch_rows: $failed
- elapsed_seconds: $elapsed

| status | count |
|---|---:|
$($summaryLines -join "`n")
"@
Set-Content -LiteralPath $ReportPath -Encoding UTF8 -Value $report
Write-Log "direct-index done attempted=$attempted indexed=$indexed failedBatchRows=$failed elapsedSeconds=$elapsed report=$ReportPath"

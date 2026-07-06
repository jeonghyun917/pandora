param(
    [string]$QdrantBaseUrl = 'http://127.0.0.1:6333',
    [string[]]$Collections = @('law_chunks', 'rag_chunks_v4', 'official_chunks'),
    [string]$OutputDir = 'runtime\backups\qdrant'
)

$ErrorActionPreference = 'Stop'

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$manifest = @()

foreach ($collection in $Collections) {
    try {
        $url = "$($QdrantBaseUrl.TrimEnd('/'))/collections/$collection/snapshots"
        $response = Invoke-WebRequest -UseBasicParsing -Method POST -Uri $url -TimeoutSec 120
        $body = $response.Content | ConvertFrom-Json
        $snapshotName = $body.result.name
        $manifest += [ordered]@{
            collection = $collection
            snapshot = $snapshotName
            status = $response.StatusCode
        }
        Write-Host "[qdrant] snapshot created collection=$collection name=$snapshotName"
    } catch {
        $manifest += [ordered]@{
            collection = $collection
            snapshot = $null
            status = 'FAILED'
            error = $_.Exception.Message
        }
        Write-Warning "[qdrant] snapshot failed collection=$collection message=$($_.Exception.Message)"
    }
}

$manifestPath = Join-Path $OutputDir "qdrant-snapshots-$timestamp.json"
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Host "[qdrant] manifest: $manifestPath"

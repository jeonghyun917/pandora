param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [ValidateRange(1, 2000)]
    [int]$Limit = 500,
    [string]$AdminToken = $env:PANDORA_ADMIN_TOKEN
)

$ErrorActionPreference = "Stop"
$endpoint = "$($BaseUrl.TrimEnd('/'))/api/admin/rag/search-index"
$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($AdminToken)) {
    $headers["X-Pandora-Admin-Token"] = $AdminToken.Trim()
}

while ($true) {
    $status = Invoke-RestMethod -Method Get -Uri "$endpoint/status" -Headers $headers
    Write-Host ("[pandora] RAG lexical index ready={0} missing={1}" -f $status.ready, $status.missingChunks)
    if ($status.ready -and [int]$status.missingChunks -eq 0) {
        break
    }

    $result = Invoke-RestMethod -Method Post -Uri "$endpoint/backfill?limit=$Limit" -Headers $headers
    Write-Host (
        "[pandora] processed={0} terms={1} remaining={2}" -f
        $result.processedChunks,
        $result.indexedTerms,
        $result.remainingChunks
    )
    if ([int]$result.processedChunks -eq 0 -and [int]$result.remainingChunks -gt 0) {
        throw "RAG lexical index backfill made no progress."
    }
}

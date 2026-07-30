param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [string]$SourceJar = '',
    [switch]$Force,
    [switch]$AllowRunningCopy
)

$ErrorActionPreference = 'Stop'

$sourceJar = if ([string]::IsNullOrWhiteSpace($SourceJar)) {
    Join-Path $ProjectDir 'target\pandora-0.0.1-SNAPSHOT.jar'
} elseif ([System.IO.Path]::IsPathRooted($SourceJar)) {
    $SourceJar
} else {
    Join-Path $ProjectDir $SourceJar
}
$runtimeDir = Join-Path $ProjectDir 'runtime\batch'
$targetJar = Join-Path $runtimeDir 'pandora-batch-runner.jar'
$metaPath = Join-Path $runtimeDir 'pandora-batch-runner.meta.json'
$minimumFatJarBytes = 10000000

if (-not (Test-Path $sourceJar)) {
    throw "Source jar does not exist: $sourceJar. Build the app first."
}

$source = Get-Item -LiteralPath $sourceJar
if ($source.Length -lt $minimumFatJarBytes -and -not $Force) {
    throw "Source jar looks too small for a Spring Boot fat jar: $($source.Length) bytes. Rebuild with '.\mvnw.cmd -DskipTests package' after stopping any process that locks target\pandora-0.0.1-SNAPSHOT.jar. Use -Force only when you intentionally promote a non-fat jar."
}

$port18080 = netstat -ano | Select-String ':18080\s+.*LISTENING'
if ($port18080 -and -not $AllowRunningCopy) {
    throw "Port 18080 is running. Stop the batch runner first. Copying over a running Spring Boot jar can corrupt the live process. Use -AllowRunningCopy only for an explicit emergency override."
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
Copy-Item -LiteralPath $source.FullName -Destination $targetJar -Force

$commit = $null
$branch = $null
try {
    $commit = (& git -C $ProjectDir rev-parse --short HEAD 2>$null).Trim()
    $branch = (& git -C $ProjectDir branch --show-current 2>$null).Trim()
} catch {
    $commit = $null
    $branch = $null
}

$meta = [ordered]@{
    promotedAt = (Get-Date).ToString('o')
    sourceJar = $source.FullName
    targetJar = (Get-Item -LiteralPath $targetJar).FullName
    sourceLastWriteTime = $source.LastWriteTime.ToString('o')
    sourceLength = $source.Length
    gitBranch = $branch
    gitCommit = $commit
}

$meta | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metaPath -Encoding UTF8

Write-Host "[pandora] Promoted batch runner jar:"
Write-Host "  source: $($source.FullName)"
Write-Host "  target: $targetJar"
Write-Host "  meta:   $metaPath"

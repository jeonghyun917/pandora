$ErrorActionPreference = 'Stop'

$projectDir = 'C:\dev\workspace-egov\pandora'
$logDir = Join-Path $projectDir 'runtime\batch\logs'
$logPath = Join-Path $logDir 'qdrant-admin-restart.log'

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Log {
    param([string] $Message)
    "[$(Get-Date -Format o)] $Message" | Add-Content -Path $logPath -Encoding UTF8
}

function Invoke-QdrantJson {
    param([string] $Path)

    $uri = "http://127.0.0.1:6333$Path"
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 5
    return $response.Content | ConvertFrom-Json
}

function Wait-QdrantReady {
    $deadline = (Get-Date).AddSeconds(60)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $collections = Invoke-QdrantJson '/collections'
            Write-Log "qdrant API ready; collections=$($collections.result.collections.Count)"
            return
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Seconds 2
        }
    }
    throw "Qdrant API did not become ready within 60 seconds. lastError=$lastError"
}

function Assert-CollectionGreen {
    param([string] $Collection)

    $deadline = (Get-Date).AddSeconds(60)
    $lastStatus = $null
    $lastOptimizer = $null
    while ((Get-Date) -lt $deadline) {
        $json = Invoke-QdrantJson "/collections/$Collection"
        $lastStatus = [string]$json.result.status
        $lastOptimizer = $json.result.optimizer_status | ConvertTo-Json -Compress -Depth 8
        Write-Log "collection=$Collection status=$lastStatus optimizer=$lastOptimizer"
        if ($lastStatus.ToLowerInvariant() -eq 'green') {
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "Qdrant collection $Collection did not become green. status=$lastStatus optimizer=$lastOptimizer"
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Log 'not running as administrator'
    throw 'This script must run from an elevated PowerShell session.'
}

Write-Log 'stopping existing qdrant processes'
Get-Process -Name qdrant -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Log "stopping qdrant pid=$($_.Id)"
    Stop-Process -Id $_.Id -Force
}

Start-Sleep -Seconds 3

$qdrantScript = Join-Path $projectDir 'scripts\start-qdrant.cmd'
Write-Log "starting Qdrant via $qdrantScript"
Start-Process -FilePath $qdrantScript -WindowStyle Hidden
Write-Log 'qdrant restart command submitted'
Wait-QdrantReady
Assert-CollectionGreen 'law_chunks'
Assert-CollectionGreen 'rag_chunks_v4'
Write-Log 'qdrant restart verified'

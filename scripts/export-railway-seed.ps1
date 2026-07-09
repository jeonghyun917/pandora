param(
    [string]$Database = 'pandora',
    [string]$User = 'pandora',
    [string]$Password = '',
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3306,
    [string]$OutputDir = 'runtime\exports\railway-seed',
    [string]$DumpExe = 'C:\Program Files\MariaDB 12.2\bin\mariadb-dump.exe',
    [switch]$IncludeAdminUsers
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DumpExe)) {
    throw "mariadb-dump was not found: $DumpExe"
}

if (-not $Password) {
    $Password = $env:PANDORA_DB_PASSWORD
}
if (-not $Password) {
    throw "Database password is required. Pass -Password or set PANDORA_DB_PASSWORD."
}

$schemaPath = Join-Path $PSScriptRoot '..\src\main\resources\schema.sql'
if (-not (Test-Path -LiteralPath $schemaPath)) {
    throw "schema.sql was not found: $schemaPath"
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$exportDir = Join-Path $OutputDir $timestamp
New-Item -ItemType Directory -Force -Path $exportDir | Out-Null

$commonArgs = @(
    "--host=$HostName",
    "--port=$Port",
    "--user=$User",
    '--skip-ssl',
    '--default-character-set=utf8mb4',
    '--single-transaction',
    '--quick',
    '--no-create-info',
    '--skip-triggers',
    '--compact'
)

function Invoke-SeedDump {
    param(
        [string]$FileName,
        [string[]]$Tables,
        [string]$Where = ''
    )

    $outPath = Join-Path $exportDir $FileName
    $errPath = Join-Path $exportDir ($FileName + '.err.log')
    $arguments = @()
    $arguments += $commonArgs
    if ($Where) {
        $arguments += "--where=$Where"
    }
    $arguments += $Database
    $arguments += $Tables

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $DumpExe
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = ($arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join ' '

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo

    $stdoutFile = [System.IO.File]::Create($outPath)
    $stderrFile = [System.IO.File]::Create($errPath)
    $exitCode = $null
    try {
        [void]$process.Start()
        $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync($stdoutFile)
        $stderrTask = $process.StandardError.BaseStream.CopyToAsync($stderrFile)
        $process.WaitForExit()
        [void]$stdoutTask.GetAwaiter().GetResult()
        [void]$stderrTask.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
    } finally {
        $stdoutFile.Dispose()
        $stderrFile.Dispose()
        $process.Dispose()
    }

    if ($exitCode -ne 0) {
        throw "mariadb-dump failed for $FileName with exit code $exitCode. See $errPath"
    }
}

function ConvertTo-ProcessArgument {
    param([string]$Value)

    if ($null -eq $Value) {
        return '""'
    }
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + ($Value -replace '"', '\"') + '"'
}

$previousMysqlPwd = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $Password

    Copy-Item -LiteralPath $schemaPath -Destination (Join-Path $exportDir '01-schema.sql') -Force

    Invoke-SeedDump `
        -FileName '02-law-core-data.sql' `
        -Tables @(
            'law_api_documents',
            'law_api_document_details',
            'law_api_document_chunks',
            'law_api_chunk_embeddings'
        )

    Invoke-SeedDump `
        -FileName '03-rag-documents.sql' `
        -Tables @('rag_documents') `
        -Where "use_yn = 'Y'"

    Invoke-SeedDump `
        -FileName '04-rag-active-chunks.sql' `
        -Tables @('rag_document_chunks') `
        -Where "use_yn = 'Y'"

    Invoke-SeedDump `
        -FileName '05-rag-active-embeddings.sql' `
        -Tables @('rag_chunk_embeddings') `
        -Where "chunk_id IN (SELECT chunk_id FROM rag_document_chunks WHERE use_yn = 'Y')"

    Invoke-SeedDump `
        -FileName '06-rag-collection-sources.sql' `
        -Tables @('rag_collection_sources')

    if ($IncludeAdminUsers) {
        Invoke-SeedDump `
            -FileName '07-admin-user.sql' `
            -Tables @('admin_user')
    }
} finally {
    if ($null -eq $previousMysqlPwd) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    } else {
        $env:MYSQL_PWD = $previousMysqlPwd
    }
}

$manifestPath = Join-Path $exportDir 'README.txt'
@(
    'Pandora Railway seed export',
    "Generated: $(Get-Date -Format o)",
    '',
    'Import files in numeric order.',
    'Included by default:',
    '- schema.sql copy',
    '- law API documents/details/chunks/embeddings',
    '- active RAG documents/chunks/embeddings',
    '- RAG collection source metadata',
    '',
    'Excluded by default:',
    '- semantic batch jobs and batch job chunks',
    '- import/sync/search failure logs',
    '- original document assets',
    '- admin users unless -IncludeAdminUsers is passed'
) | Set-Content -Path $manifestPath -Encoding UTF8

Write-Host "[railway-seed] export written: $exportDir"

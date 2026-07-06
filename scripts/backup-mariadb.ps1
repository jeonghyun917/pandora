param(
    [string]$Database = 'pandora',
    [string]$User = 'pandora',
    [string]$Password = '',
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3306,
    [string]$OutputDir = 'runtime\backups\mariadb',
    [string]$DumpExe = 'C:\Program Files\MariaDB 12.2\bin\mariadb-dump.exe'
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

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dumpPath = Join-Path $OutputDir "$Database-$timestamp.sql"
$errPath = Join-Path $OutputDir "$Database-$timestamp.err.log"

$previousMysqlPwd = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $Password
    $arguments = @(
        "--host=$HostName",
        "--port=$Port",
        "--user=$User",
        '--skip-ssl',
        '--default-character-set=utf8mb4',
        '--single-transaction',
        '--routines',
        '--events',
        $Database
    )

    $process = Start-Process -FilePath $DumpExe `
        -ArgumentList $arguments `
        -RedirectStandardOutput $dumpPath `
        -RedirectStandardError $errPath `
        -NoNewWindow `
        -Wait `
        -PassThru

    if ($process.ExitCode -ne 0) {
        throw "mariadb-dump failed with exit code $($process.ExitCode). See $errPath"
    }
} finally {
    if ($null -eq $previousMysqlPwd) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    } else {
        $env:MYSQL_PWD = $previousMysqlPwd
    }
}

Write-Host "[mariadb] backup written: $dumpPath"

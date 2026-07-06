param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$OutputName = 'rag-eval-gate-full-latest',
    [int]$CaseBatchSize = 10,
    [int]$RequestTimeoutMs = 180000,
    [int]$InterBatchSleepMs = 300,
    [switch]$Resume
)

$ErrorActionPreference = 'Stop'

$logDir = Join-Path $ProjectDir 'logs'
$evalScript = Join-Path $ProjectDir 'scripts\rag-eval-gate.js'
$consoleLog = Join-Path $logDir "$OutputName.console.log"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Set-Location -LiteralPath $ProjectDir

function Set-Or-ClearEnv([string]$Name, [string]$Value) {
    if ($null -eq $Value -or $Value -eq '') {
        Remove-Item "Env:\$Name" -ErrorAction SilentlyContinue
    } else {
        Set-Item "Env:\$Name" $Value
    }
}

Set-Or-ClearEnv 'RAG_EVAL_BASE_URL' $BaseUrl
Set-Or-ClearEnv 'RAG_EVAL_OUTPUT' (Join-Path $logDir "$OutputName.json")
Set-Or-ClearEnv 'RAG_EVAL_REPORT' (Join-Path $logDir "$OutputName.md")
Set-Or-ClearEnv 'RAG_EVAL_CHECKPOINT' (Join-Path $logDir "$OutputName.checkpoint.json")
Set-Or-ClearEnv 'RAG_EVAL_CASE_BATCH_SIZE' ([string]$CaseBatchSize)
Set-Or-ClearEnv 'RAG_EVAL_REQUEST_TIMEOUT_MS' ([string]$RequestTimeoutMs)
Set-Or-ClearEnv 'RAG_EVAL_INTER_BATCH_SLEEP_MS' ([string]$InterBatchSleepMs)
Set-Or-ClearEnv 'RAG_EVAL_CASE_LIMIT' ''
Set-Or-ClearEnv 'RAG_EVAL_CASE_IDS' ''
Set-Or-ClearEnv 'RAG_EVAL_RESUME' ($(if ($Resume) { 'true' } else { '' }))

$header = "[{0}] full rag eval start baseUrl={1} output={2}" -f (Get-Date).ToString('o'), $BaseUrl, $env:RAG_EVAL_OUTPUT
$header | Tee-Object -FilePath $consoleLog -Append

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & node $evalScript 2>&1 | Tee-Object -FilePath $consoleLog -Append
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$footer = "[{0}] full rag eval finished exitCode={1}" -f (Get-Date).ToString('o'), $exitCode
$footer | Tee-Object -FilePath $consoleLog -Append
exit $exitCode

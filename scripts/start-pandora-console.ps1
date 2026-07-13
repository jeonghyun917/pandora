param(
    [ValidateSet('app-dev', 'batch-runner')]
    [string]$Role = 'app-dev',
    [int]$Port = 0,
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [switch]$UseClasspath,
    [switch]$DryRun,
    [switch]$ConfirmBatchRunner
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'pandora-runtime.ps1')

if ($Port -le 0) {
    $Port = Get-PandoraDefaultPort -Role $Role
}

if ($Role -eq 'batch-runner' -and -not $ConfirmBatchRunner) {
    throw "Starting batch-runner requires -ConfirmBatchRunner. Do not start 18080 unless the batch owner approved it."
}

$paths = Get-PandoraRuntimePaths -ProjectDir $ProjectDir -Role $Role -Port $Port
New-Item -ItemType Directory -Force -Path $paths.LogDir | Out-Null
Clear-PandoraStalePidFile -PidFile $paths.PidFile

if (Test-PandoraPortListening -Port $Port) {
    $listenerPid = Get-PandoraPortListenerPid -Port $Port
    throw "Port $Port is already listening by pid=$listenerPid. Stop it before starting $Role."
}

$javaExe = Resolve-PandoraJavaExe -RequireConsoleExecutable
$useJar = if ($Role -eq 'app-dev') { -not $UseClasspath } else { $true }
$arguments = Get-PandoraJavaArguments -Role $Role -Port $Port -ProjectDir $ProjectDir -UseJar $useJar
$commandLine = ConvertTo-PandoraCommandLine -Executable $javaExe -Arguments $arguments

Write-Host "[pandora] console start plan"
Write-Host "  role: $Role"
Write-Host "  port: $Port"
Write-Host "  project: $ProjectDir"
Write-Host "  java: $javaExe"
Write-Host "  mode: $(if ($useJar) { 'jar' } else { 'classpath' })"
Write-Host "  command: $commandLine"
Write-Host ""

if ($DryRun) {
    Write-Host "[pandora] dry run only; server was not started."
    exit 0
}

Write-Host "[pandora] starting in this console. Keep this window open; close it or press Ctrl+C to stop."
Push-Location $ProjectDir
try {
    & $javaExe @arguments
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $exitCode

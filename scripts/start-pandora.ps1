param(
    [ValidateSet('app-dev', 'batch-runner')]
    [string]$Role = 'app-dev',
    [int]$Port = 0,
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [switch]$UseJar,
    [switch]$ConfirmBatchRunner,
    [switch]$DryRun
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

$javaExe = Resolve-PandoraJavaExe
$arguments = Get-PandoraJavaArguments `
    -Role $Role `
    -Port $Port `
    -ProjectDir $ProjectDir `
    -UseJar ([bool]$UseJar)

if ($DryRun) {
    $commandLine = ConvertTo-PandoraCommandLine -Executable $javaExe -Arguments $arguments
    Write-Host "[pandora] hidden start plan"
    Write-Host "  role: $Role"
    Write-Host "  port: $Port"
    Write-Host "  command: $commandLine"
    Write-Host "[pandora] dry run only; server was not started."
    exit 0
}

Repair-PandoraProcessPathEnvironment
$vbsPath = Join-Path $paths.RuntimeDir "pandora-$Port-start-hidden.vbs"
$launchFile = Join-Path $paths.RuntimeDir "pandora-$Port-launch.json"
$launchSpec = [ordered]@{
    javaExe = $javaExe
    arguments = $arguments
    projectDir = $ProjectDir
    outLog = $paths.OutLog
    errLog = $paths.ErrLog
}
$launchSpec | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $launchFile -Encoding UTF8

$vbsCurrentDirectory = ($ProjectDir -replace '[\r\n]+', '') -replace '"', '""'
$vbsExecutable = ($javaExe -replace '[\r\n]+', '') -replace '"', '""'
$vbsArgumentParts = foreach ($argument in $arguments) {
    Quote-PandoraArgument $argument
}
$vbsArguments = (($vbsArgumentParts -join ' ') -replace '[\r\n]+', ' ') -replace '"', '""'
$vbsLines = @(
    'Option Explicit',
    'Dim shell, executable, arguments, command',
    'Set shell = CreateObject("WScript.Shell")',
    ('shell.CurrentDirectory = "{0}"' -f $vbsCurrentDirectory),
    ('executable = "{0}"' -f $vbsExecutable),
    ('arguments = "{0}"' -f $vbsArguments),
    'command = executable & " " & arguments',
    'shell.Run command, 0, False'
)
$vbsLines | Set-Content -LiteralPath $vbsPath -Encoding ASCII

$wscript = Join-Path $env:SystemRoot 'System32\wscript.exe'
$taskName = "Pandora-$Role-$Port-$([Guid]::NewGuid().ToString('N'))"
$taskCreated = $false
$launcherPid = $null
try {
    try {
        $taskStartTime = (Get-Date).AddMinutes(1).ToString('HH:mm')
        $taskAction = ('{0} "{1}"' -f $wscript, $vbsPath)
        & schtasks.exe /Create /TN $taskName /SC ONCE /ST $taskStartTime /TR $taskAction /F | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "schtasks /Create failed with exit code $LASTEXITCODE"
        }
        $taskCreated = $true
        & schtasks.exe /Run /TN $taskName | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "schtasks /Run failed with exit code $LASTEXITCODE"
        }
    } catch {
        Write-Warning "Task Scheduler launcher failed. Falling back to direct hidden Java launch. $($_.Exception.Message)"
        $directProcess = Start-Process `
            -FilePath $javaExe `
            -ArgumentList $arguments `
            -WorkingDirectory $ProjectDir `
            -WindowStyle Hidden `
            -PassThru
        $launcherPid = $directProcess.Id
    }

    $listenerPid = Wait-PandoraPortListenerPid -Port $Port -TimeoutSeconds 60
    if (-not $listenerPid) {
        throw "Started $Role but port $Port did not begin listening within 60 seconds. Check $($paths.OutLog) and $($paths.ErrLog)"
    }

    Set-Content -LiteralPath $paths.PidFile -Value ([string]$listenerPid) -Encoding ASCII
    Write-Host "[pandora] started $Role on port $Port pid=$listenerPid launcherPid=$launcherPid"
    Write-Host "[pandora] logs: $($paths.OutLog) / $($paths.ErrLog)"
} finally {
    if ($taskCreated) {
        & schtasks.exe /Delete /TN $taskName /F | Out-Null
    }
}

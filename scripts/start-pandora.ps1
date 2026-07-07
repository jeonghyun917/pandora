param(
    [ValidateSet('app-dev', 'batch-runner')]
    [string]$Role = 'app-dev',
    [int]$Port = 0,
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [switch]$UseJar
)

$ErrorActionPreference = 'Stop'

$javaExe = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe'
$minimumFatJarBytes = 10000000

function Repair-ProcessPathEnvironment {
    $processEnvironment = [Environment]::GetEnvironmentVariables('Process')
    if ($processEnvironment.Contains('Path') -and $processEnvironment.Contains('PATH')) {
        $pathValue = [Environment]::GetEnvironmentVariable('Path', 'Process')
        if (-not $pathValue) {
            $pathValue = [Environment]::GetEnvironmentVariable('PATH', 'Process')
        }
        [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
        [Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')
    }
}

function Quote-CmdArgument([string]$Value) {
    '"' + ($Value -replace '"', '\"') + '"'
}

if ($Port -le 0) {
    $Port = if ($Role -eq 'batch-runner') { 18080 } else { 8080 }
}

$runtimeDir = Join-Path $ProjectDir "runtime\$Role"
$logDir = Join-Path $runtimeDir 'logs'
$pidFile = Join-Path $runtimeDir "pandora-$Port.pid"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-PortListening([int]$Value) {
    $line = netstat -ano | Select-String (":$Value\s+.*LISTENING")
    return [bool]$line
}

function Get-PortListenerPid([int]$Value) {
    $listeners = netstat -ano | Select-String (":$Value\s+.*LISTENING")
    foreach ($line in $listeners) {
        $parts = ($line.Line -split '\s+') | Where-Object { $_ }
        if ($parts.Length -gt 0) {
            return [int]$parts[-1]
        }
    }
    return $null
}

function Wait-PortListenerPid([int]$Value, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $pidValue = Get-PortListenerPid $Value
        if ($pidValue) {
            return $pidValue
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $null
}

if (Test-PortListening $Port) {
    throw "Port $Port is already listening. Stop the existing process before starting $Role."
}

if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "Java executable not found: $javaExe"
}

$arguments = @()
if ($Role -eq 'batch-runner') {
    $jar = Join-Path $ProjectDir 'runtime\batch\pandora-batch-runner.jar'
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Batch runner jar is missing: $jar. Run scripts\promote-batch-runner.ps1 after a successful package build."
    }
    $jarItem = Get-Item -LiteralPath $jar
    if ($jarItem.Length -lt $minimumFatJarBytes) {
        throw "Batch runner jar is too small to be trusted: $($jarItem.Length) bytes."
    }
    $arguments = @(
        "-Dserver.port=$Port",
        "-Dspring.batch.job.enabled=false",
        "-Dfile.encoding=UTF-8",
        "-jar",
        $jar,
        "--law-ai.batch.scheduler-enabled=true",
        "--logging.file.name=$logDir\batch-runner-$Port-spring.log"
    )
} else {
    if ($UseJar) {
        $jar = Join-Path $ProjectDir 'target\pandora-0.0.1-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) {
            throw "App jar is missing: $jar"
        }
        $jarItem = Get-Item -LiteralPath $jar
        if ($jarItem.Length -lt $minimumFatJarBytes) {
            throw "App jar is too small to be trusted: $($jarItem.Length) bytes. Use classpath mode or rebuild the fat jar."
        }
        $arguments = @(
            "-Dserver.port=$Port",
            "-Dspring.batch.job.enabled=false",
            "-Dfile.encoding=UTF-8",
            "-jar",
            $jar,
            "--law-ai.batch.scheduler-enabled=false",
            "--logging.file.name=$logDir\app-$Port-spring.log"
        )
    } else {
        $argsPath = Join-Path $ProjectDir 'target\server-logs\pandora-java.args'
        if (-not (Test-Path -LiteralPath $argsPath)) {
            & node (Join-Path $ProjectDir 'scripts\build-java-args.js')
        }
        if (-not (Test-Path -LiteralPath $argsPath)) {
            throw "Classpath argfile was not created: $argsPath"
        }
        $arguments = @(
            "@$argsPath",
            "--server.port=$Port",
            "--law-ai.batch.scheduler-enabled=false",
            "--logging.file.name=$logDir\app-$Port-classes-spring.log"
        )
    }
}

$outLog = Join-Path $logDir "pandora-$Port.out.log"
$errLog = Join-Path $logDir "pandora-$Port.err.log"
Repair-ProcessPathEnvironment
$vbsPath = Join-Path $runtimeDir "pandora-$Port-start-hidden.vbs"
$launchFile = Join-Path $runtimeDir "pandora-$Port-launch.json"
$launchSpec = [ordered]@{
    javaExe = $javaExe
    arguments = $arguments
    projectDir = $ProjectDir
    outLog = $outLog
    errLog = $errLog
}
$launchSpec | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $launchFile -Encoding UTF8
$javaCommandParts = @((Quote-CmdArgument $javaExe))
foreach ($argument in $arguments) {
    $javaCommandParts += (Quote-CmdArgument $argument)
}
$javaCommandParts += @(
    '1>>',
    (Quote-CmdArgument $outLog),
    '2>>',
    (Quote-CmdArgument $errLog)
)
$javaCommand = $javaCommandParts -join ' '
$launcherCommand = 'cmd.exe /c "' + $javaCommand + '"'
$vbsCurrentDirectory = ($ProjectDir -replace '[\r\n]+', '') -replace '"', '""'
$vbsCommand = ($launcherCommand -replace '[\r\n]+', ' ') -replace '"', '""'
$vbsLines = @(
    'Option Explicit',
    'Dim shell, command',
    'Set shell = CreateObject("WScript.Shell")',
    ('shell.CurrentDirectory = "{0}"' -f $vbsCurrentDirectory),
    ('command = "{0}"' -f $vbsCommand),
    'shell.Run command, 0, False'
)
$vbsLines | Set-Content -LiteralPath $vbsPath -Encoding ASCII

$wscript = Join-Path $env:SystemRoot 'System32\wscript.exe'
$taskName = "Pandora-$Role-$Port-$([Guid]::NewGuid().ToString('N'))"
$taskCreated = $false
try {
    $taskStartTime = (Get-Date).AddMinutes(1).ToString('HH:mm')
    $taskAction = ('wscript.exe "{0}"' -f $vbsPath)
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
    Write-Warning "Task Scheduler launcher failed. Falling back to direct hidden launcher. $($_.Exception.Message)"
    & $wscript $vbsPath
    if ($LASTEXITCODE -ne 0) {
        throw "Hidden launcher failed with exit code $LASTEXITCODE"
    }
}
$launcherPid = $null

$listenerPid = Wait-PortListenerPid $Port 60
if ($taskCreated) {
    & schtasks.exe /Delete /TN $taskName /F | Out-Null
}
if (-not $listenerPid) {
    throw "Started $Role but port $Port did not begin listening within 60 seconds. Check $outLog and $errLog"
}

Set-Content -LiteralPath $pidFile -Value ([string]$listenerPid) -Encoding ASCII
Write-Host "[pandora] started $Role on port $Port pid=$listenerPid launcherPid=$launcherPid"
Write-Host "[pandora] logs: $outLog / $errLog"

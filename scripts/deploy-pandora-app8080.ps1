param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [string]$StagedJar = '',
    [string]$ExpectedSha256 = '',
    [ValidateRange(10, 300)]
    [int]$HealthTimeoutSeconds = 90,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ProjectDir = [System.IO.Path]::GetFullPath($ProjectDir)

$serviceName = 'PandoraApp8080'
$servicePort = 8080
$minimumFatJarBytes = 10000000
$appJar = Join-Path $ProjectDir 'target\pandora-0.0.1-SNAPSHOT.jar'
$pidFile = Join-Path $ProjectDir 'runtime\app-dev\pandora-8080.pid'
$stopScript = Join-Path $ProjectDir 'scripts\stop-pandora.ps1'
$runtimeHelper = Join-Path $ProjectDir 'scripts\pandora-runtime.ps1'
$runtimeInfoUrl = 'http://127.0.0.1:8080/api/law-data/ai/debug/runtime-info'

if (-not (Test-Path -LiteralPath $runtimeHelper)) {
    throw "Pandora runtime helper is missing: $runtimeHelper"
}
. $runtimeHelper

function Test-Administrator {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Resolve-ProjectPath {
    param(
        [string]$Path,
        [string]$DefaultRelativePath
    )

    $candidate = if ([string]::IsNullOrWhiteSpace($Path)) { $DefaultRelativePath } else { $Path }
    if (-not [System.IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $ProjectDir $candidate
    }
    return [System.IO.Path]::GetFullPath($candidate)
}

function Get-ListeningPids {
    param([int]$Port)

    return @(netstat -ano |
        Select-String (":$Port\s+.*LISTENING") |
        ForEach-Object {
            $parts = ($_.Line -split '\s+') | Where-Object { $_ }
            if ($parts.Length -gt 0) {
                [int]$parts[-1]
            }
        } |
        Sort-Object -Unique)
}

function Get-ProtectedRuntimeSnapshot {
    $batchService = Get-Service -Name 'PandoraBatch18080' -ErrorAction SilentlyContinue
    return [ordered]@{
        batchServiceExists = [bool]$batchService
        batchServiceStatus = if ($batchService) { [string]$batchService.Status } else { $null }
        batchServiceStartType = if ($batchService) { [string]$batchService.StartType } else { $null }
        port18080Pids = @(Get-ListeningPids -Port 18080)
        port6333Pids = @(Get-ListeningPids -Port 6333)
    }
}

function Assert-ProtectedRuntimeUnchanged {
    param(
        [System.Collections.IDictionary]$Before,
        [System.Collections.IDictionary]$After
    )

    $beforeJson = ConvertTo-Json $Before -Compress -Depth 6
    $afterJson = ConvertTo-Json $After -Compress -Depth 6
    if ($beforeJson -cne $afterJson) {
        throw "Protected runtime state changed unexpectedly. before=$beforeJson after=$afterJson"
    }
}

function Assert-ServiceRetired {
    param([switch]$PlanningOnly)

    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if (-not $service) {
        Write-Host "[pandora] $serviceName is not installed; no LocalSystem service transition is needed."
        return
    }

    if ($service.Status -eq 'Stopped' -and $service.StartType -eq 'Disabled') {
        return
    }
    $message = "$serviceName must be Stopped + Disabled before non-elevated deployment. current=$($service.Status)/$($service.StartType)"
    if ($PlanningOnly) {
        Write-Warning "$message. Run set-pandora-app8080-user-runtime.ps1 -Action Prepare once with elevation."
        return
    }
    throw "$message. Run the one-time transition script first."
}

function Assert-ManagedDirectListener {
    $listeners = @(Get-ListeningPids -Port $servicePort)
    if ($listeners.Count -eq 0) {
        return
    }
    if ($listeners.Count -ne 1) {
        throw "Refusing to stop ambiguous port $servicePort listeners: $($listeners -join ',')"
    }
    if (-not (Test-Path -LiteralPath $pidFile)) {
        throw "Refusing to stop unmanaged port $servicePort listener pid=$($listeners[0]); PID file is missing."
    }

    $pidText = Get-Content -LiteralPath $pidFile -ErrorAction Stop | Select-Object -First 1
    $managedPid = 0
    if (-not [int]::TryParse([string]$pidText, [ref]$managedPid) -or $managedPid -ne $listeners[0]) {
        throw "Refusing to stop port $servicePort listener because PID file does not match. listener=$($listeners[0]) pidFile=$pidText"
    }

    $process = Get-Process -Id $managedPid -ErrorAction Stop
    if ($process.ProcessName -notin @('java', 'javaw')) {
        throw "Refusing to stop non-Java port $servicePort listener pid=$managedPid process=$($process.ProcessName)"
    }
}

function Invoke-PandoraScript {
    param(
        [string]$ScriptPath,
        [string[]]$Arguments
    )

    if (-not (Test-Path -LiteralPath $ScriptPath)) {
        throw "Required Pandora runtime script is missing: $ScriptPath"
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Pandora runtime script failed with exit code ${LASTEXITCODE}: $ScriptPath"
    }
}

function Wait-PortClosed {
    param([int]$TimeoutSeconds = 45)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (@(Get-ListeningPids -Port $servicePort).Count -eq 0) {
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Port $servicePort did not close within $TimeoutSeconds seconds."
}

function Wait-RuntimeInfo {
    param(
        [string]$ExpectedArtifactSha256,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    do {
        try {
            $runtimeInfo = Invoke-RestMethod -Uri $runtimeInfoUrl -Method Get -TimeoutSec 5
            $reportedHash = [string]$runtimeInfo.runtimeArtifactSha256
            if ($reportedHash -and $reportedHash.ToUpperInvariant() -eq $ExpectedArtifactSha256) {
                return $runtimeInfo
            }
            $lastError = "runtime hash mismatch: expected=$ExpectedArtifactSha256 actual=$reportedHash"
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "8080 runtime identity did not become ready within $TimeoutSeconds seconds: $lastError"
}

function Start-SupervisedAppDevRuntime {
    param(
        [string]$ExpectedArtifactSha256,
        [System.Collections.IDictionary]$ProtectedRuntimeBefore
    )

    $runtimePaths = Get-PandoraRuntimePaths -ProjectDir $ProjectDir -Role app-dev -Port $servicePort
    New-Item -ItemType Directory -Force -Path $runtimePaths.LogDir | Out-Null
    Clear-PandoraStalePidFile -PidFile $runtimePaths.PidFile
    if (Test-PandoraPortListening -Port $servicePort) {
        $listenerPid = Get-PandoraPortListenerPid -Port $servicePort
        throw "Port $servicePort became occupied before start by pid=$listenerPid."
    }

    $javaExe = Resolve-PandoraJavaExe -RequireConsoleExecutable
    $javaArguments = Get-PandoraJavaArguments `
        -Role app-dev `
        -Port $servicePort `
        -ProjectDir $ProjectDir `
        -UseJar $true
    $argumentLine = ($javaArguments | ForEach-Object { Quote-PandoraArgument $_ }) -join ' '

    Repair-PandoraProcessPathEnvironment
    $process = Start-Process `
        -FilePath $javaExe `
        -ArgumentList $argumentLine `
        -WorkingDirectory $ProjectDir `
        -NoNewWindow `
        -RedirectStandardOutput $runtimePaths.OutLog `
        -RedirectStandardError $runtimePaths.ErrLog `
        -PassThru

    $ready = $false
    try {
        $listenerPid = Wait-PandoraPortListenerPid -Port $servicePort -TimeoutSeconds $HealthTimeoutSeconds
        if (-not $listenerPid) {
            throw "Java pid=$($process.Id) did not open port $servicePort within $HealthTimeoutSeconds seconds."
        }
        if ($listenerPid -ne $process.Id) {
            throw "Port $servicePort listener does not match the supervised Java process. expected=$($process.Id) actual=$listenerPid"
        }
        Set-Content -LiteralPath $runtimePaths.PidFile -Value ([string]$listenerPid) -Encoding ASCII

        $runtimeInfo = Wait-RuntimeInfo `
            -ExpectedArtifactSha256 $ExpectedArtifactSha256 `
            -TimeoutSeconds $HealthTimeoutSeconds
        Assert-ProtectedRuntimeUnchanged `
            -Before $ProtectedRuntimeBefore `
            -After (Get-ProtectedRuntimeSnapshot)
        $ready = $true

        Write-Host '[pandora] app-dev 8080 deployment completed and supervised.'
        Write-Host "  pid: $listenerPid"
        Write-Host "  deployed SHA-256: $ExpectedArtifactSha256"
        Write-Host "  runtime instance: $($runtimeInfo.runtimeInstanceId)"
        Write-Host "  runtime artifact: $($runtimeInfo.runtimeArtifactKind)"
        Write-Host '  lifecycle: keep this Codex terminal session running; use stop-pandora.ps1 to stop cleanly.'

        $process.WaitForExit()
        Write-Host "[pandora] supervised app-dev process exited with code $($process.ExitCode)."
        return 0
    } catch {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        throw
    } finally {
        if (Test-Path -LiteralPath $runtimePaths.PidFile) {
            $recordedPid = Get-Content -LiteralPath $runtimePaths.PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
            if ([string]$recordedPid -eq [string]$process.Id) {
                Remove-Item -LiteralPath $runtimePaths.PidFile -Force
            }
        }
        if (-not $ready) {
            Write-Warning "Supervised app-dev startup failed. Check $($runtimePaths.OutLog) and $($runtimePaths.ErrLog)."
        }
    }
}

$resolvedStagedJar = Resolve-ProjectPath -Path $StagedJar -DefaultRelativePath 'target-stage\pandora-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $resolvedStagedJar)) {
    throw "Staged app-dev JAR is missing: $resolvedStagedJar"
}
$stagedItem = Get-Item -LiteralPath $resolvedStagedJar
if ($stagedItem.Length -lt $minimumFatJarBytes) {
    throw "Staged app-dev JAR is too small to be trusted: $($stagedItem.Length) bytes"
}

$normalizedExpectedHash = $ExpectedSha256.Trim().ToUpperInvariant()
if ($normalizedExpectedHash -and $normalizedExpectedHash -notmatch '^[0-9A-F]{64}$') {
    throw "ExpectedSha256 must contain exactly 64 hexadecimal characters."
}
$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedStagedJar).Hash.ToUpperInvariant()
if ($normalizedExpectedHash -and $sourceHash -ne $normalizedExpectedHash) {
    throw "Staged JAR hash mismatch. expected=$normalizedExpectedHash actual=$sourceHash"
}

Assert-ServiceRetired -PlanningOnly:$DryRun
$listeners = @(Get-ListeningPids -Port $servicePort)

Write-Host '[pandora] app-dev 8080 deployment plan'
Write-Host "  source: $resolvedStagedJar"
Write-Host "  source SHA-256: $sourceHash"
Write-Host "  target: $appJar"
Write-Host "  current 8080 listeners: $($listeners -join ',')"
Write-Host '  start mode: non-elevated user-owned app-dev process'

if ($DryRun) {
    Write-Host '[pandora] dry run only; no service, process, JAR, 18080, or Qdrant state was changed.'
    exit 0
}
if (Test-Administrator) {
    throw 'Refusing to deploy from an elevated shell; app-dev 8080 must run as the current non-admin user.'
}

$protectedBefore = Get-ProtectedRuntimeSnapshot
Assert-ManagedDirectListener
if (@(Get-ListeningPids -Port $servicePort).Count -gt 0) {
    Invoke-PandoraScript `
        -ScriptPath $stopScript `
        -Arguments @('-Role', 'app-dev', '-Port', '8080', '-ProjectDir', $ProjectDir)
    Wait-PortClosed
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $appJar) | Out-Null
Copy-Item -LiteralPath $resolvedStagedJar -Destination $appJar -Force
$deployedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $appJar).Hash.ToUpperInvariant()
if ($deployedHash -ne $sourceHash) {
    throw "Deployed JAR hash mismatch. source=$sourceHash deployed=$deployedHash"
}

$supervisorExitCode = Start-SupervisedAppDevRuntime `
    -ExpectedArtifactSha256 $deployedHash `
    -ProtectedRuntimeBefore $protectedBefore
exit $supervisorExitCode

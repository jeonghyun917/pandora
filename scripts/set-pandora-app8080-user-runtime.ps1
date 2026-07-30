param(
    [ValidateSet('Prepare', 'Restore')]
    [string]$Action = 'Prepare',
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ProjectDir = [System.IO.Path]::GetFullPath($ProjectDir)
$script:TransitionScriptPath = $PSCommandPath

$serviceName = 'PandoraApp8080'
$serviceRole = 'app-dev'
$servicePort = 8080
$expectedServiceAccount = 'LocalSystem'
$expectedServicePath = Join-Path $ProjectDir 'runtime\services\PandoraApp8080\PandoraApp8080.exe'
$serviceControlScript = Join-Path $ProjectDir 'scripts\start-pandora-service.ps1'
$manifestPath = Join-Path $ProjectDir 'runtime\app-dev\PandoraApp8080-user-runtime-transition.json'
$servicePropertiesPath = Join-Path $ProjectDir 'runtime\app-dev\pandora-service.properties'

function Test-Administrator {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-ElevatedSelf {
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Action {1} -ProjectDir "{2}"' -f `
        $script:TransitionScriptPath,
        $Action,
        $ProjectDir
    Write-Host "[pandora] requesting one-time administrator approval for $Action on $serviceName only."
    $process = Start-Process `
        -FilePath $windowsPowerShell `
        -Verb RunAs `
        -ArgumentList $arguments `
        -Wait `
        -PassThru
    exit $process.ExitCode
}

function Get-NormalizedExecutablePath {
    param([string]$PathName)

    $expanded = [Environment]::ExpandEnvironmentVariables($PathName).Trim()
    if ($expanded.StartsWith('"')) {
        $closingQuote = $expanded.IndexOf('"', 1)
        if ($closingQuote -le 1) {
            throw "Invalid quoted service executable path: $PathName"
        }
        $expanded = $expanded.Substring(1, $closingQuote - 1)
    }
    return [System.IO.Path]::GetFullPath($expanded.Trim('"'))
}

function Get-AppServiceIdentity {
    $service = Get-Service -Name $serviceName -ErrorAction Stop
    if (-not $service) {
        throw "Required Windows service is not installed: $serviceName"
    }

    $serviceRegistryPath = "Registry::HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\$serviceName"
    $serviceConfig = Get-ItemProperty `
        -LiteralPath $serviceRegistryPath `
        -Name ImagePath, ObjectName `
        -ErrorAction Stop

    $actualPath = Get-NormalizedExecutablePath -PathName ([string]$serviceConfig.ImagePath)
    $expectedPath = [System.IO.Path]::GetFullPath($expectedServicePath)
    if (-not [string]::Equals($actualPath, $expectedPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to control unexpected $serviceName executable. expected=$expectedPath actual=$actualPath"
    }
    if (-not [string]::Equals([string]$serviceConfig.ObjectName, $expectedServiceAccount, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to transition unexpected $serviceName account. expected=$expectedServiceAccount actual=$($serviceConfig.ObjectName)"
    }

    return [pscustomobject]@{
        Name = [string]$service.Name
        Path = $actualPath
        Account = [string]$serviceConfig.ObjectName
        State = [string]$service.Status
        StartMode = [string]$service.StartType
    }
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

function Wait-ServiceState {
    param(
        [ValidateSet('Running', 'Stopped')]
        [string]$ExpectedState,
        [int]$TimeoutSeconds = 45
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $service = Get-Service -Name $serviceName -ErrorAction Stop
        if ([string]$service.Status -eq $ExpectedState) {
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "$serviceName did not reach state=$ExpectedState within $TimeoutSeconds seconds."
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

function Invoke-OfficialServiceControl {
    param(
        [ValidateSet('Start', 'Stop')]
        [string]$ControlAction
    )

    if (-not (Test-Path -LiteralPath $serviceControlScript)) {
        throw "Official Pandora service script is missing: $serviceControlScript"
    }
    & powershell.exe `
        -NoProfile `
        -ExecutionPolicy Bypass `
        -File $serviceControlScript `
        -Action $ControlAction `
        -Role $serviceRole `
        -Port $servicePort `
        -ProjectDir $ProjectDir
    if ($LASTEXITCODE -ne 0) {
        throw "Official $serviceName $ControlAction failed with exit code $LASTEXITCODE."
    }
}

function Convert-StartModeForSetService {
    param([string]$StartMode)

    switch ($StartMode) {
        'Auto' { return 'Automatic' }
        'Automatic' { return 'Automatic' }
        'Manual' { return 'Manual' }
        'Disabled' { return 'Disabled' }
        default { throw "Unsupported service start mode in rollback manifest: $StartMode" }
    }
}

function Grant-ServicePropertiesRead {
    param(
        [string]$Path,
        [System.Security.Principal.SecurityIdentifier]$IdentitySid
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $grantArgument = '*' + $IdentitySid.Value + ':(R)'
    & icacls.exe $Path /grant $grantArgument
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to grant read-only service properties access to SID $IdentitySid; icacls exit=$LASTEXITCODE"
    }

    $verifiedAcl = Get-Acl -LiteralPath $Path
    $matchingAllowMask = [System.Security.AccessControl.FileSystemRights]0
    foreach ($access in $verifiedAcl.Access) {
        if ($access.AccessControlType -eq 'Allow' -and $access.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]) -eq $IdentitySid) {
            $matchingAllowMask = $matchingAllowMask -bor $access.FileSystemRights
        }
    }
    if (($matchingAllowMask -band [System.Security.AccessControl.FileSystemRights]::Read) -ne [System.Security.AccessControl.FileSystemRights]::Read) {
        throw "Failed to grant read-only service properties access to SID $IdentitySid."
    }
}

function Restore-ServicePropertiesAcl {
    param(
        [string]$Path,
        [string]$Sddl
    )

    if (-not $Sddl -or -not (Test-Path -LiteralPath $Path)) {
        return
    }
    $restoredAcl = New-Object System.Security.AccessControl.FileSecurity
    $restoredAcl.SetSecurityDescriptorSddlForm(
        $Sddl,
        [System.Security.AccessControl.AccessControlSections]::Access
    )
    [System.IO.File]::SetAccessControl($Path, $restoredAcl)
}

$identity = Get-AppServiceIdentity
$protectedBefore = Get-ProtectedRuntimeSnapshot

if ($Action -eq 'Prepare') {
    Write-Host '[pandora] one-time app-dev service transition'
    Write-Host "  service: $($identity.Name)"
    Write-Host "  account: $($identity.Account)"
    Write-Host "  path:    $($identity.Path)"
    Write-Host "  state:   $($identity.State)"
    Write-Host "  mode:    $($identity.StartMode)"
    Write-Host '  target:  stopped + disabled; future 8080 runs as the current non-admin user'

    if ($DryRun) {
        Write-Host '[pandora] dry run only; no service, process, file, 18080, or Qdrant state was changed.'
        exit 0
    }
    if (-not (Test-Administrator)) {
        Invoke-ElevatedSelf
    }

    $runtimeIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $servicePropertiesSddlBefore = if (Test-Path -LiteralPath $servicePropertiesPath) {
        (Get-Acl -LiteralPath $servicePropertiesPath).GetSecurityDescriptorSddlForm(
            [System.Security.AccessControl.AccessControlSections]::Access
        )
    } else {
        $null
    }

    $manifest = [ordered]@{
        schemaVersion = 1
        capturedAt = (Get-Date).ToUniversalTime().ToString('o')
        projectDir = [System.IO.Path]::GetFullPath($ProjectDir)
        serviceName = $identity.Name
        servicePath = $identity.Path
        serviceAccount = $identity.Account
        originalState = $identity.State
        originalStartMode = $identity.StartMode
        runtimeUserAccount = $runtimeIdentity.Name
        runtimeUserSid = $runtimeIdentity.User.Value
        servicePropertiesPath = $servicePropertiesPath
        servicePropertiesSddlBefore = $servicePropertiesSddlBefore
        protectedRuntimeBefore = $protectedBefore
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $manifestPath) | Out-Null
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

    Grant-ServicePropertiesRead -Path $servicePropertiesPath -IdentitySid $runtimeIdentity.User

    if ($identity.State -ne 'Stopped') {
        Invoke-OfficialServiceControl -ControlAction Stop
        Wait-ServiceState -ExpectedState Stopped
    }
    Wait-PortClosed
    Set-Service -Name $serviceName -StartupType Disabled

    $verified = Get-AppServiceIdentity
    if ($verified.State -ne 'Stopped' -or $verified.StartMode -ne 'Disabled') {
        throw "Transition verification failed. state=$($verified.State) mode=$($verified.StartMode)"
    }
    Assert-ProtectedRuntimeUnchanged -Before $protectedBefore -After (Get-ProtectedRuntimeSnapshot)

    Write-Host "[pandora] prepared user-owned app-dev runtime; rollback manifest: $manifestPath"
    exit 0
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Rollback manifest is missing: $manifestPath"
}
$rollback = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($rollback.schemaVersion -ne 1 -or $rollback.serviceName -ne $serviceName) {
    throw "Invalid rollback manifest for $serviceName."
}
if (-not [string]::Equals([string]$rollback.servicePath, $identity.Path, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Rollback manifest service path mismatch. manifest=$($rollback.servicePath) actual=$($identity.Path)"
}

$restoreMode = Convert-StartModeForSetService -StartMode ([string]$rollback.originalStartMode)
$restoreRunning = [string]$rollback.originalState -eq 'Running'
Write-Host '[pandora] app-dev service rollback'
Write-Host "  service: $serviceName"
Write-Host "  restore mode: $restoreMode"
Write-Host "  restore running: $restoreRunning"
if ($DryRun) {
    Write-Host '[pandora] dry run only; no service, process, file, 18080, or Qdrant state was changed.'
    exit 0
}
if (-not (Test-Administrator)) {
    Invoke-ElevatedSelf
}

if ($restoreRunning -and $identity.State -ne 'Running' -and @(Get-ListeningPids -Port $servicePort).Count -gt 0) {
    throw "Refusing to restore the LocalSystem service while a user-owned process is listening on port $servicePort. Stop app-dev 8080 first."
}

Set-Service -Name $serviceName -StartupType $restoreMode
if ($restoreRunning) {
    Invoke-OfficialServiceControl -ControlAction Start
    Wait-ServiceState -ExpectedState Running
}
if ($rollback.PSObject.Properties.Name -contains 'servicePropertiesSddlBefore') {
    Restore-ServicePropertiesAcl `
        -Path ([string]$rollback.servicePropertiesPath) `
        -Sddl ([string]$rollback.servicePropertiesSddlBefore)
}
Assert-ProtectedRuntimeUnchanged -Before $protectedBefore -After (Get-ProtectedRuntimeSnapshot)
Write-Host '[pandora] restored the recorded app-dev service state.'

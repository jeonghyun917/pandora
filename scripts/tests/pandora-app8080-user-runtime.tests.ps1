param(
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:Passed = 0

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw "ASSERT TRUE failed: $Message"
    }
    $script:Passed++
}

function Assert-Equal {
    param(
        $Expected,
        $Actual,
        [string]$Message
    )

    $expectedJson = ConvertTo-Json $Expected -Compress -Depth 8
    $actualJson = ConvertTo-Json $Actual -Compress -Depth 8
    if ($expectedJson -cne $actualJson) {
        throw "ASSERT EQUAL failed: $Message`nexpected=$expectedJson`nactual=$actualJson"
    }
    $script:Passed++
}

function Get-ListeningPid {
    param([int]$Port)

    $pids = @(netstat -ano |
        Select-String (":$Port\s+.*LISTENING") |
        ForEach-Object {
            $parts = ($_.Line -split '\s+') | Where-Object { $_ }
            if ($parts.Length -gt 0) {
                [int]$parts[-1]
            }
        } |
        Sort-Object -Unique)
    return @($pids)
}

function Get-ServiceSnapshot {
    param([string]$Name)

    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if (-not $service) {
        return [ordered]@{ exists = $false }
    }
    return [ordered]@{
        exists = $true
        status = [string]$service.Status
        startType = [string]$service.StartType
    }
}

function Get-FileSha256OrNull {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
}

function Get-RuntimeSnapshot {
    $appJar = Join-Path $ProjectDir 'target\pandora-0.0.1-SNAPSHOT.jar'
    return [ordered]@{
        port8080 = @(Get-ListeningPid -Port 8080)
        port18080 = @(Get-ListeningPid -Port 18080)
        port6333 = @(Get-ListeningPid -Port 6333)
        appService = Get-ServiceSnapshot -Name 'PandoraApp8080'
        batchService = Get-ServiceSnapshot -Name 'PandoraBatch18080'
        appJarSha256 = Get-FileSha256OrNull -Path $appJar
    }
}

function Assert-NoDangerousOverrideParameters {
    param(
        [string]$ScriptPath,
        [string[]]$AllowedParameters
    )

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $ScriptPath,
        [ref]$tokens,
        [ref]$parseErrors
    )
    Assert-Equal -Expected 0 -Actual $parseErrors.Count -Message "$ScriptPath parses without errors"
    $actual = @($ast.ParamBlock.Parameters |
        ForEach-Object { $_.Name.VariablePath.UserPath } |
        Sort-Object)
    $expected = @($AllowedParameters | Sort-Object)
    Assert-Equal -Expected $expected -Actual $actual -Message "$ScriptPath exposes only its fixed-scope parameters"
}

$transitionScript = Join-Path $ProjectDir 'scripts\set-pandora-app8080-user-runtime.ps1'
$deployScript = Join-Path $ProjectDir 'scripts\deploy-pandora-app8080.ps1'
$stagedJar = Join-Path $ProjectDir 'target-stage\pandora-0.0.1-SNAPSHOT.jar'

Assert-True -Condition (Test-Path -LiteralPath $transitionScript) -Message 'one-time transition script exists'
Assert-True -Condition (Test-Path -LiteralPath $deployScript) -Message 'non-elevated deployment script exists'
Assert-True -Condition (Test-Path -LiteralPath $stagedJar) -Message 'staged app-dev JAR exists for dry-run validation'

Assert-NoDangerousOverrideParameters `
    -ScriptPath $transitionScript `
    -AllowedParameters @('Action', 'DryRun', 'ProjectDir')
Assert-NoDangerousOverrideParameters `
    -ScriptPath $deployScript `
    -AllowedParameters @('DryRun', 'ExpectedSha256', 'HealthTimeoutSeconds', 'ProjectDir', 'StagedJar')

$scriptTexts = @(
    Get-Content -Raw -LiteralPath $transitionScript
    Get-Content -Raw -LiteralPath $deployScript
) -join "`n"
Assert-True -Condition ($scriptTexts -notmatch '(?i)-Role\s+batch-runner') -Message 'scripts never invoke the batch-runner role'
Assert-True -Condition ($scriptTexts -notmatch '(?i)(Stop-Service|Start-Service|Set-Service).{0,120}PandoraBatch18080') -Message 'scripts never mutate the batch service'
$port18080Lines = @($scriptTexts -split '\r?\n' | Where-Object { $_ -match '\b18080\b' })
Assert-True `
    -Condition (@($port18080Lines | Where-Object { $_ -notmatch 'Get-ListeningPids|-Role\s+batch-runner|no service, process|no service, process, JAR' }).Count -eq 0) `
    -Message 'every 18080 reference is read-only observation or a negative safety assertion'
$port6333Lines = @($scriptTexts -split '\r?\n' | Where-Object { $_ -match '\b6333\b' })
Assert-True `
    -Condition (@($port6333Lines | Where-Object { $_ -notmatch 'Get-ListeningPids|no service, process|no service, process, JAR' }).Count -eq 0) `
    -Message 'every 6333 reference is read-only observation or a negative safety assertion'

$before = Get-RuntimeSnapshot

& $transitionScript -Action Prepare -ProjectDir $ProjectDir -DryRun
if ($LASTEXITCODE -ne 0) {
    throw "Transition dry-run failed with exit code $LASTEXITCODE"
}

$expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedJar).Hash
& $deployScript `
    -ProjectDir $ProjectDir `
    -StagedJar $stagedJar `
    -ExpectedSha256 $expectedHash `
    -DryRun
if ($LASTEXITCODE -ne 0) {
    throw "Deployment dry-run failed with exit code $LASTEXITCODE"
}

$invalidHashFailed = $false
try {
    & $deployScript `
        -ProjectDir $ProjectDir `
        -StagedJar $stagedJar `
        -ExpectedSha256 'NOT-A-SHA256' `
        -DryRun
} catch {
    $invalidHashFailed = $true
}
Assert-True -Condition $invalidHashFailed -Message 'deployment rejects malformed trusted SHA-256 values'

$after = Get-RuntimeSnapshot
Assert-Equal -Expected $before -Actual $after -Message 'all dry-runs preserve services, ports, and deployed JAR'

Write-Host "[pandora-test] PASS assertions=$script:Passed"

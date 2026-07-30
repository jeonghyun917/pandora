param(
    [ValidateSet('app-dev', 'batch-runner')]
    [string]$Role = 'app-dev',
    [int]$Port = 0,
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [switch]$FromCurrentUserEnvironment
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'pandora-runtime.ps1')

if (-not $FromCurrentUserEnvironment) {
    throw "Pass -FromCurrentUserEnvironment to copy OPENAI_API_KEY into the ignored service-only properties file."
}

if ($Port -le 0) {
    $Port = Get-PandoraDefaultPort -Role $Role
}

$apiKey = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY', 'Process')
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw "OPENAI_API_KEY is not available in the current user process. Set it first, then run this script again."
}

$paths = Get-PandoraRuntimePaths -ProjectDir $ProjectDir -Role $Role -Port $Port
New-Item -ItemType Directory -Force -Path $paths.RuntimeDir | Out-Null

$contents = @(
    '# Generated local service configuration. This file is ignored by Git.',
    '# Keep this file restricted to the interactive user, SYSTEM, and local administrators.',
    "law-ai.openai.api-key=$apiKey"
)
Set-Content -LiteralPath $paths.ServiceProperties -Value $contents -Encoding UTF8

try {
    $acl = Get-Acl -LiteralPath $paths.ServiceProperties
    $acl.SetAccessRuleProtection($true, $false)
    $identities = @(
        [System.Security.Principal.WindowsIdentity]::GetCurrent().User,
        [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
        [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    )
    foreach ($identity in $identities) {
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
            $identity,
            'FullControl',
            'Allow'
        )
        [void]$acl.SetAccessRule($rule)
    }
    Set-Acl -LiteralPath $paths.ServiceProperties -AclObject $acl
} catch {
    Write-Warning "Service properties were written, but ACL hardening could not be completed: $($_.Exception.Message)"
}

Write-Host "[pandora] wrote ignored service properties for ${Role}: $($paths.ServiceProperties)"
Write-Host "[pandora] OPENAI_API_KEY was not printed. Re-render and restart the matching service to apply it."

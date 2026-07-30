[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('plan', 'apply')]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [string]$ManifestPath
)

$ErrorActionPreference = 'Stop'
$resolvedManifest = [System.IO.Path]::GetFullPath($ManifestPath)
$env:PANDORA_OBJECT_STORAGE_MIGRATION_ENABLED = 'true'
$env:PANDORA_OBJECT_STORAGE_MIGRATION_MODE = $Mode
$env:PANDORA_OBJECT_STORAGE_MIGRATION_MANIFEST_PATH = $resolvedManifest

& .\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spring.main.web-application-type=none'
exit $LASTEXITCODE

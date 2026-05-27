$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "frontend\dist"
$static = Join-Path $root "src\main\resources\static"
$targetStatic = Join-Path $root "target\classes\static"

if (!(Test-Path $dist)) {
    throw "frontend\dist does not exist. Run npm run build first."
}

foreach ($destination in @($static, $targetStatic)) {
    if (Test-Path $destination) {
        Remove-Item -LiteralPath $destination -Recurse -Force
    }
    New-Item -ItemType Directory -Path $destination | Out-Null
    Copy-Item -Path (Join-Path $dist "*") -Destination $destination -Recurse -Force
}

Write-Host "Synced frontend dist to Spring static directories."

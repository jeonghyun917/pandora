param(
    [Parameter(Mandatory = $true)][string]$TestRoot,
    [string]$BinaryPath = 'C:\dev\tools\qdrant\qdrant.exe'
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = (Resolve-Path -LiteralPath $TestRoot).Path
$allowed = [IO.Path]::GetFullPath((Join-Path $workspace 'runtime')) + [IO.Path]::DirectorySeparatorChar
if (-not $root.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
    -not (Split-Path -Leaf $root).StartsWith('qdrant-recovery-test-')) {
    throw 'Dedicated worktree runtime/qdrant-recovery-test-* directory required.'
}
$config = Join-Path $root 'config.yaml'
$normalized = $root.Replace('\', '/')
$expected = @"
storage:
  storage_path: '$normalized/storage'
  snapshots_path: '$normalized/snapshots'
service:
  host: 127.0.0.1
  http_port: 26333
  grpc_port: 26334
telemetry_disabled: true
"@
if ((Get-Content -LiteralPath $config -Raw).Replace("`r`n", "`n").Trim() -cne $expected.Replace("`r`n", "`n").Trim()) {
    throw 'Isolated Qdrant configuration does not match the fenced test contract.'
}
if (Get-NetTCPConnection -LocalPort 26333,26334 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Dedicated Qdrant test ports are already occupied; no process was stopped.'
}
Write-Output "Recovery test Qdrant configuration: $config"
& $BinaryPath --config-path $config
exit $LASTEXITCODE

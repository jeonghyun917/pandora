param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$TestRoot,
    [string]$BinaryPath = 'C:\dev\tools\qdrant\qdrant.exe'
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = (Resolve-Path -LiteralPath $TestRoot).Path
$allowed = [IO.Path]::GetFullPath((Join-Path $workspace 'runtime')) + [IO.Path]::DirectorySeparatorChar
if (-not $root.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
    -not (Split-Path -Leaf $root).StartsWith('qdrant-recovery-test-')) {
    throw 'Dedicated test runtime directory required.'
}
$config = Join-Path $root 'config.yaml'
$p = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId"
$pattern = '--config-path\s+"?' + [regex]::Escape($config) + '"?(?:\s|$)'
if (-not $p -or $p.ExecutablePath -ine (Resolve-Path -LiteralPath $BinaryPath).Path -or $p.CommandLine -notmatch $pattern) {
    throw 'Qdrant test process executable or configuration ownership mismatch.'
}
foreach ($port in @(26333,26334)) {
    $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop)
    if ($listeners.Count -ne 1 -or $listeners[0].OwningProcess -ne $ProcessId) {
        throw 'Qdrant test listener ownership mismatch.'
    }
}
if (Get-NetTCPConnection -LocalPort 6333,6334 -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.OwningProcess -eq $ProcessId }) {
    throw 'Shared Qdrant process is forbidden for recovery tests.'
}
Write-Output 'Dedicated Qdrant test process and listeners verified.'

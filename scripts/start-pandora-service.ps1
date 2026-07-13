param(
    [ValidateSet('Install', 'Uninstall', 'Start', 'Stop', 'Restart', 'Status', 'RenderConfig')]
    [string]$Action = 'Status',
    [ValidateSet('app-dev', 'batch-runner')]
    [string]$Role = 'app-dev',
    [int]$Port = 0,
    [string]$ProjectDir = 'C:\dev\workspace-egov\pandora',
    [string]$ServiceName = '',
    [string]$WinSWExe = '',
    [ValidateSet('Manual', 'Automatic')]
    [string]$StartMode = 'Manual',
    [switch]$UseClasspath,
    [switch]$ConfirmBatchRunner
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'pandora-runtime.ps1')

function Get-DefaultPandoraServiceName {
    param(
        [ValidateSet('app-dev', 'batch-runner')]
        [string]$Role,
        [int]$Port
    )

    if ($Role -eq 'batch-runner') {
        return "PandoraBatch$Port"
    }
    return "PandoraApp$Port"
}

function Resolve-WinSWSource {
    param(
        [string]$ProjectDir,
        [string]$WinSWExe
    )

    $candidates = @()
    if ($WinSWExe) {
        $candidates += $WinSWExe
    }
    if ($env:PANDORA_WINSW_EXE) {
        $candidates += $env:PANDORA_WINSW_EXE
    }
    $candidates += @(
        (Join-Path $ProjectDir 'tools\winsw\WinSW.exe'),
        (Join-Path $ProjectDir 'tools\winsw\winsw.exe')
    )

    $resolved = $candidates |
        Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
        Select-Object -First 1

    if (-not $resolved) {
        throw "WinSW executable was not found. Put WinSW.exe at tools\winsw\WinSW.exe, pass -WinSWExe, or set PANDORA_WINSW_EXE."
    }

    return $resolved
}

function Add-TextElement {
    param(
        [System.Xml.XmlDocument]$Document,
        [System.Xml.XmlElement]$Parent,
        [string]$Name,
        [string]$Value
    )

    $element = $Document.CreateElement($Name)
    $element.InnerText = $Value
    [void]$Parent.AppendChild($element)
}

function Write-WinSWConfig {
    param(
        [string]$ConfigPath,
        [string]$ServiceName,
        [string]$DisplayName,
        [string]$Description,
        [string]$JavaExe,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [string]$LogDir,
        [string]$StartMode
    )

    $document = New-Object System.Xml.XmlDocument
    $declaration = $document.CreateXmlDeclaration('1.0', 'UTF-8', $null)
    [void]$document.AppendChild($declaration)
    $service = $document.CreateElement('service')
    [void]$document.AppendChild($service)

    Add-TextElement -Document $document -Parent $service -Name 'id' -Value $ServiceName
    Add-TextElement -Document $document -Parent $service -Name 'name' -Value $DisplayName
    Add-TextElement -Document $document -Parent $service -Name 'description' -Value $Description
    Add-TextElement -Document $document -Parent $service -Name 'executable' -Value $JavaExe
    Add-TextElement -Document $document -Parent $service -Name 'arguments' -Value (($Arguments | ForEach-Object { Quote-PandoraArgument $_ }) -join ' ')
    Add-TextElement -Document $document -Parent $service -Name 'workingdirectory' -Value $WorkingDirectory
    Add-TextElement -Document $document -Parent $service -Name 'logpath' -Value $LogDir
    Add-TextElement -Document $document -Parent $service -Name 'startmode' -Value $StartMode

    $document.Save($ConfigPath)
}

if ($Port -le 0) {
    $Port = Get-PandoraDefaultPort -Role $Role
}

if (-not $ServiceName) {
    $ServiceName = Get-DefaultPandoraServiceName -Role $Role -Port $Port
}

$mutatingBatchAction = $Role -eq 'batch-runner' -and $Action -in @('Install', 'Start', 'Stop', 'Restart', 'Uninstall')
if ($mutatingBatchAction -and -not $ConfirmBatchRunner) {
    throw "$Action for batch-runner requires -ConfirmBatchRunner. Do not change 18080 unless the batch owner approved it."
}

$paths = Get-PandoraRuntimePaths -ProjectDir $ProjectDir -Role $Role -Port $Port
$serviceDir = Join-Path $ProjectDir "runtime\services\$ServiceName"
$serviceExe = Join-Path $serviceDir "$ServiceName.exe"
$serviceConfig = Join-Path $serviceDir "$ServiceName.xml"
New-Item -ItemType Directory -Force -Path $paths.LogDir | Out-Null
New-Item -ItemType Directory -Force -Path $serviceDir | Out-Null

$displayName = "$ServiceName ($Role :$Port)"
$description = "Pandora $Role runtime on port $Port."

if ($Action -in @('Install', 'RenderConfig')) {
    $javaExe = Resolve-PandoraJavaExe -RequireConsoleExecutable
    $useJar = if ($Role -eq 'app-dev') { -not $UseClasspath } else { $true }
    $arguments = Get-PandoraJavaArguments -Role $Role -Port $Port -ProjectDir $ProjectDir -UseJar $useJar

    Write-WinSWConfig `
        -ConfigPath $serviceConfig `
        -ServiceName $ServiceName `
        -DisplayName $displayName `
        -Description $description `
        -JavaExe $javaExe `
        -Arguments $arguments `
        -WorkingDirectory $ProjectDir `
        -LogDir $paths.LogDir `
        -StartMode $StartMode

    Write-Host "[pandora] rendered WinSW config: $serviceConfig"
}

if ($Action -eq 'RenderConfig') {
    Write-Host "[pandora] render only; service was not installed or started."
    exit 0
}

if ($Action -eq 'Install') {
    $sourceWinSW = Resolve-WinSWSource -ProjectDir $ProjectDir -WinSWExe $WinSWExe
    Copy-Item -LiteralPath $sourceWinSW -Destination $serviceExe -Force
    & $serviceExe install
    exit $LASTEXITCODE
}

if ($Action -eq 'Status') {
    $service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($service) {
        Write-Host ("[pandora] service {0}: {1}" -f $ServiceName, $service.Status)
        exit 0
    }
    Write-Host "[pandora] service $ServiceName is not installed."
    exit 0
}

if (-not (Test-Path -LiteralPath $serviceExe)) {
    throw "Service wrapper is missing: $serviceExe. Run -Action Install first."
}

switch ($Action) {
    'Start' {
        & $serviceExe start
        exit $LASTEXITCODE
    }
    'Stop' {
        & $serviceExe stop
        exit $LASTEXITCODE
    }
    'Restart' {
        & $serviceExe restart
        exit $LASTEXITCODE
    }
    'Uninstall' {
        & $serviceExe uninstall
        exit $LASTEXITCODE
    }
}

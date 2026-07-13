Set-StrictMode -Version Latest

$script:PandoraMinimumFatJarBytes = 10000000

function Repair-PandoraProcessPathEnvironment {
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

function Get-PandoraDefaultPort {
    param(
        [ValidateSet('app-dev', 'batch-runner')]
        [string]$Role
    )

    if ($Role -eq 'batch-runner') {
        return 18080
    }
    return 8080
}

function Resolve-PandoraJavaExe {
    param(
        [switch]$RequireConsoleExecutable
    )

    Repair-PandoraProcessPathEnvironment

    $candidates = @()
    if ($env:PANDORA_JAVA_EXE) {
        $candidates += $env:PANDORA_JAVA_EXE
    }
    if ($env:JAVA_HOME) {
        $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }

    if ($RequireConsoleExecutable) {
        $candidates += @(
            'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe',
            'C:\PROGRA~1\ECLIPS~1\JDK-17~1.8-H\bin\java.exe'
        )
    } else {
        $candidates += @(
            'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\javaw.exe',
            'C:\PROGRA~1\ECLIPS~1\JDK-17~1.8-H\bin\javaw.exe',
            'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe',
            'C:\PROGRA~1\ECLIPS~1\JDK-17~1.8-H\bin\java.exe'
        )
    }

    $pathJava = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if ($pathJava) {
        $candidates += $pathJava.Source
    }

    $javaExe = $candidates |
        Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
        Select-Object -First 1

    if (-not $javaExe) {
        throw "Java executable was not found. Set PANDORA_JAVA_EXE or JAVA_HOME."
    }

    return $javaExe
}

function Get-PandoraRuntimePaths {
    param(
        [string]$ProjectDir,
        [ValidateSet('app-dev', 'batch-runner')]
        [string]$Role,
        [int]$Port
    )

    $runtimeDir = Join-Path $ProjectDir "runtime\$Role"
    $logDir = Join-Path $runtimeDir 'logs'
    $pidFile = Join-Path $runtimeDir "pandora-$Port.pid"

    return [pscustomobject]@{
        ProjectDir = $ProjectDir
        Role = $Role
        Port = $Port
        RuntimeDir = $runtimeDir
        LogDir = $logDir
        PidFile = $pidFile
        OutLog = (Join-Path $logDir "pandora-$Port.out.log")
        ErrLog = (Join-Path $logDir "pandora-$Port.err.log")
    }
}

function Test-PandoraPortListening {
    param([int]$Port)

    $line = netstat -ano | Select-String (":$Port\s+.*LISTENING")
    return [bool]$line
}

function Get-PandoraPortListenerPid {
    param([int]$Port)

    $listeners = netstat -ano | Select-String (":$Port\s+.*LISTENING")
    foreach ($line in $listeners) {
        $parts = ($line.Line -split '\s+') | Where-Object { $_ }
        if ($parts.Length -gt 0) {
            return [int]$parts[-1]
        }
    }
    return $null
}

function Wait-PandoraPortListenerPid {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $listenerPid = Get-PandoraPortListenerPid -Port $Port
        if ($listenerPid) {
            return $listenerPid
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    return $null
}

function Quote-PandoraArgument {
    param([string]$Value)

    if ($null -eq $Value) {
        return '""'
    }

    return '"' + ($Value -replace '"', '\"') + '"'
}

function ConvertTo-PandoraCommandLine {
    param(
        [string]$Executable,
        [string[]]$Arguments
    )

    $quotedArguments = $Arguments | ForEach-Object { Quote-PandoraArgument $_ }
    return ((Quote-PandoraArgument $Executable) + ' ' + ($quotedArguments -join ' '))
}

function Get-PandoraJavaArguments {
    param(
        [ValidateSet('app-dev', 'batch-runner')]
        [string]$Role,
        [int]$Port,
        [string]$ProjectDir,
        [bool]$UseJar
    )

    if ($Role -eq 'batch-runner') {
        $jar = Join-Path $ProjectDir 'runtime\batch\pandora-batch-runner.jar'
        if (-not (Test-Path -LiteralPath $jar)) {
            throw "Batch runner jar is missing: $jar. Run scripts\promote-batch-runner.ps1 after a verified package build."
        }

        $jarItem = Get-Item -LiteralPath $jar
        if ($jarItem.Length -lt $script:PandoraMinimumFatJarBytes) {
            throw "Batch runner jar is too small to be trusted: $($jarItem.Length) bytes."
        }

        return @(
            "-Dserver.port=$Port",
            '-Dspring.batch.job.enabled=false',
            '-Dfile.encoding=UTF-8',
            '-jar',
            $jar,
            '--law-ai.batch.scheduler-enabled=true',
            "--logging.file.name=$(Join-Path $ProjectDir "runtime\batch-runner\logs\batch-runner-$Port-spring.log")"
        )
    }

    if ($UseJar) {
        $jar = Join-Path $ProjectDir 'target\pandora-0.0.1-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) {
            throw "App jar is missing: $jar"
        }

        $jarItem = Get-Item -LiteralPath $jar
        if ($jarItem.Length -lt $script:PandoraMinimumFatJarBytes) {
            throw "App jar is too small to be trusted: $($jarItem.Length) bytes. Rebuild the Spring Boot fat jar."
        }

        return @(
            "-Dserver.port=$Port",
            '-Dspring.batch.job.enabled=false',
            '-Dfile.encoding=UTF-8',
            '-jar',
            $jar,
            '--law-ai.batch.scheduler-enabled=false',
            "--logging.file.name=$(Join-Path $ProjectDir "runtime\app-dev\logs\app-$Port-spring.log")"
        )
    }

    $argsPath = Join-Path $ProjectDir 'target\server-logs\pandora-java.args'
    if (-not (Test-Path -LiteralPath $argsPath)) {
        & node (Join-Path $ProjectDir 'scripts\build-java-args.js')
    }
    if (-not (Test-Path -LiteralPath $argsPath)) {
        throw "Classpath argfile was not created: $argsPath"
    }

    return @(
        "@$argsPath",
        "--server.port=$Port",
        '--law-ai.batch.scheduler-enabled=false',
        "--logging.file.name=$(Join-Path $ProjectDir "runtime\app-dev\logs\app-$Port-classes-spring.log")"
    )
}

function Clear-PandoraStalePidFile {
    param([string]$PidFile)

    if (-not (Test-Path -LiteralPath $PidFile)) {
        return
    }

    $pidText = Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $pidText) {
        Remove-Item -LiteralPath $PidFile -Force
        return
    }

    $process = Get-Process -Id ([int]$pidText) -ErrorAction SilentlyContinue
    if (-not $process) {
        Remove-Item -LiteralPath $PidFile -Force
    }
}

@echo off
set "PROJECT_DIR=C:\dev\workspace-egov\pandora"
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe"
set "BATCH_JAR=%PROJECT_DIR%\runtime\batch\pandora-batch-runner.jar"
set "LOG_DIR=%PROJECT_DIR%\runtime\batch\logs"

cd /d "%PROJECT_DIR%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

if not exist "%BATCH_JAR%" (
  echo [pandora] Missing batch runner jar: "%BATCH_JAR%" >> "%LOG_DIR%\batch-runner-18080.err.log"
  echo [pandora] Run scripts\promote-batch-runner.ps1 first. >> "%LOG_DIR%\batch-runner-18080.err.log"
  exit /b 1
)

"%JAVA_EXE%" "-Dserver.port=18080" "-Dspring.batch.job.enabled=false" "-Dfile.encoding=UTF-8" -jar "%BATCH_JAR%" "--logging.file.name=%LOG_DIR%\batch-runner-18080-spring.log" 1>>"%LOG_DIR%\batch-runner-18080.out.log" 2>>"%LOG_DIR%\batch-runner-18080.err.log"

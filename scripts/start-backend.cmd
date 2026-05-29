@echo off
setlocal
cd /d "%~dp0.."

set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe"
set "ARGS_FILE=target\server-logs\pandora-java.args"

if not exist "%JAVA_EXE%" (
  echo [pandora] Java executable not found: %JAVA_EXE%
  exit /b 1
)

node scripts\build-java-args.js
if errorlevel 1 exit /b 1

"%JAVA_EXE%" "@%ARGS_FILE%" >> target\server-logs\backend-direct.out.log 2>> target\server-logs\backend-direct.err.log

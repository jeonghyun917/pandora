@echo off
setlocal
cd /d "%~dp0.."
"C:\dev\tools\qdrant\qdrant.exe" --config-path "C:\dev\tools\qdrant\config\config.yaml" >> qdrant-run.log 2>> qdrant-run.err.log

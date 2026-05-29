@echo off
setlocal
cd /d "%~dp0.."
start "pandora-qdrant" /min "%~dp0start-qdrant.cmd"
timeout /t 8 /nobreak > nul
start "pandora-backend" /min "%~dp0start-backend.cmd"

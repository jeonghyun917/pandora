@echo off
setlocal
cd /d C:\dev\workspace-egov\pandora
start "pandora-qdrant" /min scripts\start-qdrant.cmd
timeout /t 8 /nobreak > nul
start "pandora-backend" /min scripts\start-backend.cmd

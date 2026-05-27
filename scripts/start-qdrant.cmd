@echo off
setlocal
set QDRANT__STORAGE__STORAGE_PATH=C:\dev\qdrant-storage
cd /d C:\dev\workspace-egov\pandora
C:\dev\tools\qdrant\qdrant.exe >> qdrant-run.log 2>> qdrant-run.err.log

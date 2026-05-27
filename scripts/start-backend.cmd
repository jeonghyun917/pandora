@echo off
setlocal
cd /d C:\dev\workspace-egov\pandora
mvnw.cmd spring-boot:run >> backend-run.log 2>> backend-run.err.log

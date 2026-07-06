@echo off
cd /d C:\dev\workspace-egov\pandora
if not exist "C:\dev\workspace-egov\pandora\target\server-logs\pandora-java.args" (
  node "C:\dev\workspace-egov\pandora\scripts\build-java-args.js"
)
call "C:\dev\workspace-egov\pandora\scripts\start-backend-8080-classes.cmd"

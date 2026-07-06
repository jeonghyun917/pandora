@echo off
cd /d C:\dev\workspace-egov\pandora
"C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe" @C:\dev\workspace-egov\pandora\target\server-logs\pandora-java.args --server.port=8080 --logging.file.name=C:\dev\workspace-egov\pandora\target\server-logs\backend-8080-classes-spring.log 1>>C:\dev\workspace-egov\pandora\target\server-logs\backend-8080-classes-cmd.out.log 2>>C:\dev\workspace-egov\pandora\target\server-logs\backend-8080-classes-cmd.err.log

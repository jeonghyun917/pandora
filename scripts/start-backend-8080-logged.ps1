Set-Location 'C:\dev\workspace-egov\pandora'
"[$(Get-Date -Format o)] starting backend 8080" | Add-Content -Path 'C:\dev\workspace-egov\pandora\logs\backend-8080-restart.out.log'
& 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe' '-Dserver.port=8080' '-Dspring.batch.job.enabled=false' -jar 'C:\dev\workspace-egov\pandora\target\pandora-0.0.1-SNAPSHOT.jar' *> 'C:\dev\workspace-egov\pandora\logs\backend-8080-restart.live.log'

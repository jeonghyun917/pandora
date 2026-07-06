Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = "C:\dev\workspace-egov\pandora"
cmd = "cmd.exe /c ""set BACKFILL_MAX_PAGES=2&& set BACKFILL_MAX_DETAILS=80&& ""C:\Program Files\nodejs\node.exe"" scripts\ministry-backfill-metadata.js >> logs\ministry-backfill-metadata-scheduler.log 2>&1"""
shell.Run cmd, 0, False

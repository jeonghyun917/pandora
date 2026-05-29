Option Explicit

Dim shell, command
Set shell = CreateObject("WScript.Shell")

command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File ""C:\dev\workspace-egov\pandora\scripts\monitor-admrul-batch.ps1"""
shell.Run command, 0, True

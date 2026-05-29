Option Explicit

Dim shell, command
Set shell = CreateObject("WScript.Shell")

command = """C:\Program Files\nodejs\node.exe"" ""C:\dev\workspace-egov\pandora\scripts\monitor-admrul-batch.js"""
shell.Run command, 0, True

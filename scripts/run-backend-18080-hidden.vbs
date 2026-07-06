Option Explicit

Dim shell, command
Set shell = CreateObject("WScript.Shell")

shell.CurrentDirectory = "C:\dev\workspace-egov\pandora"
command = """C:\Windows\System32\cmd.exe"" /c ""C:\dev\workspace-egov\pandora\scripts\start-backend-18080-logged.cmd"""
shell.Run command, 0, False

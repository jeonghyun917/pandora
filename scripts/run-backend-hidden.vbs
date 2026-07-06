Option Explicit

Dim shell, command
Set shell = CreateObject("WScript.Shell")

shell.CurrentDirectory = "C:\dev\workspace-egov\pandora"
command = """C:\dev\workspace-egov\pandora\scripts\start-backend-8080-classes.cmd"""
shell.Run command, 0, False

Option Explicit

Dim shell, command
Set shell = CreateObject("WScript.Shell")

shell.CurrentDirectory = "C:\dev\workspace-egov\pandora"
command = """C:\dev\tools\qdrant\qdrant.exe"" --config-path ""C:\dev\tools\qdrant\config\config.yaml"""
shell.Run command, 0, False

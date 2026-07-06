Option Explicit

Dim shell, command
Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = "C:\dev\workspace-egov\pandora"

If Not BackendReady() Then
  shell.Run """C:\Windows\System32\wscript.exe"" ""C:\dev\workspace-egov\pandora\scripts\run-backend-18080-hidden.vbs""", 0, False
  WScript.Sleep 15000
End If

shell.Environment("PROCESS")("PANDORA_BASE_URL") = "http://localhost:18080"
command = """C:\Program Files\nodejs\node.exe"" ""C:\dev\workspace-egov\pandora\scripts\monitor-ministry-doc-batch.js"""
shell.Run command, 0, False

Function BackendReady()
  On Error Resume Next
  Dim http
  Set http = CreateObject("MSXML2.XMLHTTP")
  http.Open "GET", "http://localhost:18080/api/rag-collection/ministry/status", False
  http.Send
  BackendReady = (Err.Number = 0 And http.Status >= 200 And http.Status < 500)
  Err.Clear
End Function

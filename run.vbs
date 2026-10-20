Option Explicit
Dim shell, scriptPath, command
Set shell = CreateObject("WScript.Shell")
scriptPath = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName) & "\start.ps1"
command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File """ & scriptPath & """"
shell.Run command, 1, False

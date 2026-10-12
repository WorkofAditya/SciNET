Set shell = CreateObject("WScript.Shell")
scriptDir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
command = """" & scriptDir & "\start_server.bat"""
shell.Run command, 0, False

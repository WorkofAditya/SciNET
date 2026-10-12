@echo off
setlocal
cd /d "%~dp0"
wscript.exe //B //NoLogo "%~dp0start_hidden.vbs"
endlocal

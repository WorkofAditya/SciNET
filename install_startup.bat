@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$startup = [Environment]::GetFolderPath('Startup'); $wsh = New-Object -ComObject WScript.Shell; $shortcut = $wsh.CreateShortcut((Join-Path $startup 'SciNET Link.lnk')); $shortcut.TargetPath = (Join-Path $env:WINDIR 'System32\wscript.exe'); $shortcut.Arguments = '//B //NoLogo "' + (Join-Path (Get-Location).Path 'start_hidden.vbs') + '"'; $shortcut.WorkingDirectory = (Get-Location).Path; $shortcut.Description = 'Start SciNET Link silently at sign-in'; $shortcut.Save()"
if errorlevel 1 (
  echo Could not install the startup shortcut.
  pause
  exit /b 1
)
echo SciNET Link will now start silently when you sign in to Windows.
echo Log file: %~dp0scinet.log
pause

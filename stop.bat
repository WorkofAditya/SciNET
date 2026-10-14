@echo off
setlocal
cd /d "%~dp0"

rem Stop only SciNET processes launched from this project folder.
set "SCINET_STOP_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "$root = [IO.Path]::GetFullPath($env:SCINET_STOP_DIR).TrimEnd('\'); $escaped = [regex]::Escape($root); $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue; $targets = $procs | Where-Object { ($_.CommandLine -and $_.CommandLine -match $escaped) -and (($_.CommandLine -match 'uvicorn(?:\.exe)?\s+server:app') -or ($_.CommandLine -match 'start\.bat.*--hidden-worker')) }; foreach ($p in $targets) { try { & taskkill.exe /PID $p.ProcessId /T /F 2>$null | Out-Null } catch {} }" >nul 2>&1
exit /b 0

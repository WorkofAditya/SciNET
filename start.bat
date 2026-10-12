@echo off
setlocal
cd /d "%~dp0"

if /i "%~1"=="--hidden-worker" goto run_server
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$args = '/d /c ""' + $env:SCINET_START_BAT + '" --hidden-worker"' ; Start-Process -FilePath $env:ComSpec -ArgumentList $args -WorkingDirectory $env:SCINET_START_DIR -WindowStyle Hidden" 
exit /b

:run_server
call :run >> "%~dp0scinet.log" 2>&1
exit /b %errorlevel%

:run
echo.
echo [%date% %time%] Starting SciNET Link...
if not exist ".venv\Scripts\python.exe" (
  py -m venv .venv
  if errorlevel 1 (
    echo ERROR: Could not create Python virtual environment. Install Python 3.11+ and enable the Python launcher.
    exit /b 1
  )
)
".venv\Scripts\python.exe" -m pip install -r requirements.txt --disable-pip-version-check
if errorlevel 1 (
  echo ERROR: Dependency installation failed. Check the internet connection and scinet.log.
  exit /b 1
)
if not exist "shared_files" mkdir "shared_files"
".venv\Scripts\python.exe" -m uvicorn server:app --host 0.0.0.0 --port 8000

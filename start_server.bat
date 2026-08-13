@echo off
chcp 65001 >nul
title SimpleLabel Server

echo ============================================
echo   SimpleLabel - Production Server (Waitress)
echo ============================================
echo.

cd /d "%~dp0"

:: Check if waitress is installed
python -c "import waitress" 2>nul
if errorlevel 1 (
    echo [ERROR] Waitress is not installed.
    echo Installing waitress...
    pip install waitress
    if errorlevel 1 (
        echo [ERROR] Failed to install waitress.
        pause
        exit /b 1
    )
    echo Waitress installed successfully.
    echo.
)

:: Create logs directory if missing
if not exist "logs" mkdir logs

:: Get local IP address
set LOCAL_IP=
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4"') do (
    if not defined LOCAL_IP (
        set "LOCAL_IP=%%a"
    )
)
if defined LOCAL_IP for /f "tokens=*" %%b in ("%LOCAL_IP%") do set LOCAL_IP=%%b

:: The work-log dashboard is restricted to this address for this server run.
if defined LOCAL_IP set "SIMPLELABEL_ADMIN_IP=%LOCAL_IP%"

echo Starting Waitress server on port 18083...
echo Local:   http://127.0.0.1:18083
if defined LOCAL_IP (
    echo Network: http://%LOCAL_IP%:18083
) else (
    echo Network: http://^<your-ip^>:18083
)
echo.
echo Press Ctrl+C to stop the server.
echo ============================================
echo.

python -m waitress --host=0.0.0.0 --port=18083 --threads=8 --channel-timeout=600 --max-request-body-size=17179869184 app:app

pause

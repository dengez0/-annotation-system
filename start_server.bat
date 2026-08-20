@echo off
chcp 65001 >nul
title SimpleLabel Java Production - 18083
cd /d "%~dp0"

echo ============================================
echo   SimpleLabel - Java Production Server
echo ============================================
echo.
echo Starting the current Java build on port 18083...
echo.

call "%~dp0start_java_server.bat"

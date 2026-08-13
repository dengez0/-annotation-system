@echo off
chcp 65001 >nul
title SimpleLabel Java Production - 18083
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run_java_backend.ps1" -Mode Production
pause

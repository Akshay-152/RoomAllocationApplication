@echo off
setlocal EnableExtensions
title Close Room Allocation Servers

echo.
echo ============================================
echo  Closing Room Allocation project servers
echo ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0CLOS.ps1"
if errorlevel 1 (
    echo.
    echo One or more project processes could not be stopped.
    echo Other applications and ports were left untouched.
    pause
    exit /b 1
)

echo.
echo Only identifiable processes belonging to this project were stopped.
echo Servers started manually with "npm start" are left untouched.
echo.
pause

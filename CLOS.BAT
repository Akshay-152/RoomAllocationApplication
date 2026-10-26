@echo off
setlocal EnableExtensions
title Close All Servers

rem ===========================================================================
rem  CLOS.BAT - stop every local server started by this workspace
rem    1. Notification server  (node src/server.js - any port)
rem    2. Known server ports   (3000 notification, 8080/8090 exam app)
rem    3. Java exam app        (Spring Boot jar or mvn spring-boot:run)
rem  Data and .env are never touched - this only stops processes.
rem ===========================================================================

echo.
echo ============================================
echo  Closing all local servers
echo ============================================

rem --- 1. Notification server (node src/server.js, whatever port) -----------
echo.
echo [1/3] Stopping notification server (node src/server.js) ...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='node.exe'\" | Where-Object { $_.CommandLine -like '*src/server.js*' } | ForEach-Object { Write-Host ('        stopping node PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

rem --- 2. Anything still listening on the project's server ports ------------
echo [2/3] Freeing ports 3000, 8080, 8090 ...
for %%P in (3000 8080 8090) do (
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr /r ":%%P .*LISTENING"') do (
        echo        port %%P held by PID %%a - killing
        taskkill /F /PID %%a >nul 2>&1
    )
)

rem --- 3. Java exam app (jar or spring-boot:run) ----------------------------
echo [3/3] Stopping Java exam app ...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*room-allocation*' -or $_.CommandLine -like '*spring-boot*' -or $_.CommandLine -like '*exam.allocation*' } | ForEach-Object { Write-Host ('        stopping java PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

echo.
echo ============================================
echo  Done - all local servers are stopped.
echo.
echo  Start again with:
echo    notification server:  cd notification ^&^& npm start
echo    exam app:             cd "java project" ^&^& mvn spring-boot:run
echo ============================================
echo.
pause

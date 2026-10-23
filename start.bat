@echo off
setlocal EnableExtensions
title Exam Seating + Notification System

rem ===========================================================================
rem  start.bat - starts the whole workspace in one go
rem
rem    1. MySQL            docker compose up -d  (java project\docker-compose.yml)
rem    2. Seating system   Spring Boot on port 8080 (builds the jar on first run)
rem    3. Notification     Node server on port 3000 (npm install on first run)
rem
rem  Each server runs in its own window: close that window (or press Ctrl+C
rem  inside it) to stop that server. Nothing else is ever killed.
rem
rem  "Already running" is decided by an HTTP probe against the port, never by
rem  a raw port match: findstr splits a space-separated pattern into OR terms,
rem  which used to make unrelated listeners look like our own servers.
rem ===========================================================================

set "ROOT=%~dp0"
set "JAVA_DIR=%ROOT%java project"
set "NOTIFY_DIR=%ROOT%notification"
set "JAR=%JAVA_DIR%\target\room-allocation-system-0.0.1-SNAPSHOT.jar"
set "MVN=%JAVA_DIR%\.mvn-tool\apache-maven-3.9.9\bin\mvn.cmd"

echo.
echo ============================================
echo   Exam Seating + Notification - start
echo ============================================
echo.

rem --- 1. MySQL ---------------------------------------------------------------
echo [1/3] MySQL ...
rem A MySQL already listening on 3306 (native service or an old container)
rem means there is nothing to start - only reach Docker when 3306 is silent.
powershell -NoProfile -Command "try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect('127.0.0.1',3306); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo        MySQL already reachable on localhost:3306 - skipping Docker.
    goto mysql_done
)
where docker >nul 2>&1
if errorlevel 1 (
    echo        Docker not found - and nothing is listening on localhost:3306.
    echo        Start MySQL ^(Docker Desktop or a local service^) and run start.bat again.
    goto mysql_done
)
docker ps --filter "name=room-allocation-db" --format "{{.Names}}" 2>nul | findstr /i "room-allocation-db" >nul 2>&1
if not errorlevel 1 (
    echo        Container "room-allocation-db" is already running.
    goto mysql_done
)
pushd "%JAVA_DIR%"
docker compose version >nul 2>&1
if errorlevel 1 (
    docker-compose up -d
) else (
    docker compose up -d
)
if errorlevel 1 (
    echo        Could not start MySQL through Docker - continuing anyway.
    echo        The seating app needs a reachable database on localhost:3306.
)
popd
:mysql_done

rem --- 2. Seating system (port 8080) -----------------------------------------
echo.
echo [2/3] Seating system (port 8080) ...
where java >nul 2>&1
if errorlevel 1 (
    echo        Java was not found on PATH. Install JDK 17 or newer, then run
    echo        start.bat again.
    pause
    exit /b 1
)
if exist "%JAR%" goto build_done
if not exist "%MVN%" (
    echo        Bundled Maven is missing: %MVN%
    pause
    exit /b 1
)
echo        Building the application ^(first run only, takes a minute^) ...
call "%MVN%" -f "%JAVA_DIR%\pom.xml" package -DskipTests
if errorlevel 1 (
    echo.
    echo        Build failed - fix the errors above and run start.bat again.
    pause
    exit /b 1
)
:build_done

set "APP_STATUS=started"
rem (a) Our app already answering? Then skip the start.
powershell -NoProfile -Command "try { $null = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 'http://localhost:8080/'; exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo        Already running: http://localhost:8080 answers.
    set "APP_STATUS=already running"
    goto app_started
)
rem (b) Nothing answers - is the port held by some other program anyway?
set "PID8080="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr "LISTENING" ^| findstr /c":8080 "') do if not defined PID8080 set "PID8080=%%p"
if defined PID8080 (
    echo        Port 8080 is held by another program ^(PID %PID8080%^) that does not
    echo        answer on localhost. Close that program, then run start.bat again.
    set "APP_STATUS=NOT STARTED - port 8080 busy ^(PID %PID8080%^)"
    goto app_started
)
start "Seating system - port 8080" /d "%JAVA_DIR%" cmd /k java -jar "%JAR%"
echo        Waiting for http://localhost:8080 to answer ...
set "WAITED=0"
:wait_app
powershell -NoProfile -Command "try { $null = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 'http://localhost:8080/'; exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 goto app_ready
set /a WAITED+=4
if %WAITED% geq 120 goto app_slow
rem PowerShell sleep instead of `timeout`: timeout.exe resolves to the wrong
rem binary when the script is launched from a Git Bash / MSYS terminal.
powershell -NoProfile -Command "Start-Sleep -Seconds 4" >nul 2>&1
goto wait_app
:app_slow
echo        Still starting after 2 minutes - check the "Seating system" window.
set "APP_STATUS=starting - check its window"
goto app_started
:app_ready
echo        Ready: http://localhost:8080
set "APP_STATUS=started"
:app_started

rem --- 3. Notification server (port 3000) ------------------------------------
echo.
echo [3/3] Notification server (port 3000) ...
set "NOTIFY_STATUS=started"
if not exist "%NOTIFY_DIR%\package.json" (
    echo        No notification project found - skipping.
    set "NOTIFY_STATUS=skipped - no project"
    goto notify_done
)
where npm >nul 2>&1
if errorlevel 1 (
    echo        Node.js / npm was not found on PATH - skipping the notification
    echo        server. The seating system works without it.
    set "NOTIFY_STATUS=skipped - npm not found"
    goto notify_done
)
rem (a) Already answering? Skip the start.
powershell -NoProfile -Command "try { $null = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 'http://localhost:3000/api/config'; exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo        Already running: http://localhost:3000 answers.
    set "NOTIFY_STATUS=already running"
    goto notify_done
)
rem (b) Port held by another program?
set "PID3000="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr "LISTENING" ^| findstr /c":3000 "') do if not defined PID3000 set "PID3000=%%p"
if defined PID3000 (
    echo        Port 3000 is held by another program ^(PID %PID3000%^) that does not
    echo        answer on localhost. Close that program, then run start.bat again.
    set "NOTIFY_STATUS=NOT STARTED - port 3000 busy ^(PID %PID3000%^)"
    goto notify_done
)
if not exist "%NOTIFY_DIR%\node_modules" (
    echo        Installing notification dependencies ^(first run only^) ...
    pushd "%NOTIFY_DIR%"
    call npm install
    if errorlevel 1 (
        popd
        echo        npm install failed - run it manually inside notification\ and retry.
        set "NOTIFY_STATUS=NOT STARTED - npm install failed"
        goto notify_done
    )
    popd
)
rem Force the real port: some shells/sessions inject PORT=0, which would make
rem node listen on a random port instead of 3000.
start "Notification server - port 3000" /d "%NOTIFY_DIR%" cmd /k "set PORT=3000&& npm start"
echo        Started: http://localhost:3000
set "NOTIFY_STATUS=started"
:notify_done

rem --- Summary ---------------------------------------------------------------
echo.
echo ============================================
echo   Ready
echo ============================================
echo   Seating system  : http://localhost:8080   (admin / admin)     [%APP_STATUS%]
echo   Student login   : http://localhost:8080   (roll no / roll no) [%APP_STATUS%]
echo   Notifications   : http://localhost:3000   (subscribe page)    [%NOTIFY_STATUS%]
echo   Notify admin    : http://localhost:3000/admin-login.html      [%NOTIFY_STATUS%]
echo.
echo   MySQL           : localhost:3306, database room_allocation_db
echo   Stop a server   : close its window or press Ctrl+C inside it
echo   Stop MySQL      : docker compose stop   (run inside "java project")
echo.
pause
endlocal

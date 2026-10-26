@echo off
setlocal EnableExtensions
title Exam Seating + Notification System

rem ===========================================================================
rem  start.bat - starts the whole workspace in one go
rem
rem    1. MySQL            Docker Compose (only when port 3306 is not in use)
rem    2. Seating system   Spring Boot on port 8080
rem    3. Notification     Node server on port 3000
rem
rem  Each server runs in its own window: close that window (or press Ctrl+C
rem  inside it) to stop that server. Nothing else is ever killed.
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
powershell -NoProfile -Command "try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect('127.0.0.1',3306); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo        Database is reachable on localhost:3306.
    set "DB_STATUS=reachable"
    goto mysql_done
)
where docker >nul 2>&1
if errorlevel 1 (
    echo        Docker not found - and nothing is listening on localhost:3306.
    echo        Start MySQL or Docker Desktop, then run start.bat again.
    set "DB_STATUS=NOT READY - MySQL unavailable"
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
    echo        Could not start MySQL through Docker.
    set "DB_STATUS=NOT READY - Docker could not start MySQL"
    popd
    goto mysql_done
)
popd
echo        Waiting for MySQL to finish initializing ...
set "WAITED=0"
:wait_mysql
set "DB_HEALTH="
for /f "delims=" %%s in ('docker inspect --format^="{{.State.Health.Status}}" room-allocation-db 2^>nul') do set "DB_HEALTH=%%s"
if /i "%DB_HEALTH%"=="healthy" goto mysql_ready
if /i "%DB_HEALTH%"=="unhealthy" goto mysql_unhealthy
set /a WAITED+=4
if %WAITED% geq 120 goto mysql_timeout
powershell -NoProfile -Command "Start-Sleep -Seconds 4" >nul 2>&1
goto wait_mysql
:mysql_ready
echo        MySQL is healthy.
set "DB_STATUS=healthy"
goto mysql_done
:mysql_unhealthy
echo        MySQL reports an unhealthy state. Check Docker Desktop and its logs.
set "DB_STATUS=NOT READY - MySQL unhealthy"
goto mysql_done
:mysql_timeout
echo        MySQL did not become healthy within 2 minutes. Check Docker Desktop.
set "DB_STATUS=NOT READY - MySQL timed out"
:mysql_done

rem --- 2. Seating system (port 8080) -----------------------------------------
echo.
echo [2/3] Seating system (port 8080) ...
set "APP_STATUS=NOT STARTED - MySQL unavailable"
set "APP_READY=0"
if /i not "%DB_STATUS:~0,3%"=="NOT" goto database_ready
echo        Skipping Java because its MySQL database is not ready.
goto app_done
:database_ready
rem Accept any HTTP response (including a login redirect or 401) as proof
rem that an HTTP server is listening; Invoke-WebRequest treats 4xx as failure.
powershell -NoProfile -Command "try { $r = [System.Net.HttpWebRequest]::Create('http://127.0.0.1:8080/'); $r.Timeout = 1000; $s = $r.GetResponse(); $s.Close(); exit 0 } catch [System.Net.WebException] { if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode; $_.Exception.Response.Close(); if ($code -lt 500) { exit 0 } }; exit 1 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo        Already running: http://localhost:8080 is responding.
    set "APP_STATUS=already running"
    set "APP_READY=1"
    goto app_done
)
set "PID8080="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr "LISTENING" ^| findstr /c":8080 "') do if not defined PID8080 set "PID8080=%%p"
if defined PID8080 (
    echo        Port 8080 is held by another program ^(PID %PID8080%^).
    set "APP_STATUS=NOT STARTED - port 8080 busy"
    goto app_done
)
where java >nul 2>&1
if errorlevel 1 (
    echo        Java was not found on PATH. Install JDK 17 or newer.
    set "APP_STATUS=NOT STARTED - Java not found"
    goto app_done
)
if not exist "%MVN%" (
    where mvn >nul 2>&1
    if errorlevel 1 (
        if exist "%JAR%" (
            echo        Maven was not found; using the existing JAR without rebuilding.
            goto launch_app
        )
        echo        Maven and the application JAR are both missing.
        set "APP_STATUS=NOT STARTED - Maven and JAR missing"
        goto app_done
    )
    set "MVN=mvn"
)
echo        Building the latest application ...
call "%MVN%" -f "%JAVA_DIR%\pom.xml" package -DskipTests
if errorlevel 1 (
    echo        Build failed. Fix the Maven errors above and retry.
    set "APP_STATUS=NOT STARTED - build failed"
    goto app_done
)
:launch_app
set "APP_STATUS=starting"
start "Seating system - port 8080" /d "%JAVA_DIR%" cmd /k java -jar "%JAR%"
echo        Waiting for http://localhost:8080 to answer ...
set "WAITED=0"
:wait_app
powershell -NoProfile -Command "try { $r = [System.Net.HttpWebRequest]::Create('http://127.0.0.1:8080/'); $r.Timeout = 1000; $s = $r.GetResponse(); $s.Close(); exit 0 } catch [System.Net.WebException] { if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode; $_.Exception.Response.Close(); if ($code -lt 500) { exit 0 } }; exit 1 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 goto app_ready
set /a WAITED+=4
if %WAITED% geq 120 goto app_slow
if %WAITED% geq 20 (
    powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | Where-Object { $_.CommandLine -like '*room-allocation-system-0.0.1-SNAPSHOT.jar*' }; if ($p) { exit 0 } else { exit 1 }" >nul 2>&1
    if errorlevel 1 goto app_failed
)
rem PowerShell sleep instead of `timeout`: timeout.exe resolves to the wrong
rem binary when the script is launched from a Git Bash / MSYS terminal.
powershell -NoProfile -Command "Start-Sleep -Seconds 3" >nul 2>&1
goto wait_app
:app_slow
echo        No HTTP response after 2 minutes - check the "Seating system" window.
set "APP_STATUS=NOT READY - startup timed out"
goto app_done
:app_failed
echo        The Java process exited before the server became ready.
echo        Check the "Seating system" window for the startup error.
set "APP_STATUS=NOT READY - Java exited; check its window"
goto app_done
:app_ready
echo        Ready: http://localhost:8080
set "APP_STATUS=ready"
set "APP_READY=1"
:app_done

rem --- 3. Notification server (port 3000) ------------------------------------
echo.
echo [3/3] Notification server (port 3000) ...
set "NOTIFY_STATUS=not started"
set "NOTIFY_READY=0"
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
rem Accept any HTTP response as proof the notification server is responding.
powershell -NoProfile -Command "try { $r = [System.Net.HttpWebRequest]::Create('http://127.0.0.1:3000/api/config'); $r.Timeout = 1000; $s = $r.GetResponse(); $s.Close(); exit 0 } catch [System.Net.WebException] { if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode; $_.Exception.Response.Close(); if ($code -lt 500) { exit 0 } }; exit 1 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
    echo        Already running: http://localhost:3000 is responding.
    set "NOTIFY_STATUS=already running"
    set "NOTIFY_READY=1"
    goto notify_done
)
set "PID3000="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr "LISTENING" ^| findstr /c":3000 "') do if not defined PID3000 set "PID3000=%%p"
if defined PID3000 (
    echo        Port 3000 is held by another program ^(PID %PID3000%^).
    set "NOTIFY_STATUS=NOT STARTED - port 3000 busy"
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
rem Force local-development settings and the real port so inherited shell
rem variables cannot make the server reject its config or bind another port.
start "Notification server - port 3000" /d "%NOTIFY_DIR%" cmd /k "set NODE_ENV=development&& set PORT=3000&& npm start"
echo        Waiting for http://localhost:3000 to answer ...
set "WAITED=0"
:wait_notify
powershell -NoProfile -Command "try { $r = [System.Net.HttpWebRequest]::Create('http://127.0.0.1:3000/api/config'); $r.Timeout = 1000; $s = $r.GetResponse(); $s.Close(); exit 0 } catch [System.Net.WebException] { if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode; $_.Exception.Response.Close(); if ($code -lt 500) { exit 0 } }; exit 1 } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 goto notify_ready
set /a WAITED+=3
if %WAITED% geq 30 goto notify_slow
if %WAITED% geq 12 (
    powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter 'Name=''node.exe''' | Where-Object { $_.CommandLine -like '*src/server.js*' }; if ($p) { exit 0 } else { exit 1 }" >nul 2>&1
    if errorlevel 1 goto notify_failed
)
powershell -NoProfile -Command "Start-Sleep -Seconds 3" >nul 2>&1
goto wait_notify
:notify_ready
echo        Ready: http://localhost:3000
set "NOTIFY_STATUS=ready"
set "NOTIFY_READY=1"
goto notify_done
:notify_slow
echo        No HTTP response after 30 seconds - check the notification window.
set "NOTIFY_STATUS=NOT READY - startup timed out"
goto notify_done
:notify_failed
echo        The Node process exited before the notification server became ready.
echo        Check the notification window for the startup error.
set "NOTIFY_STATUS=NOT READY - Node exited; check its window"
:notify_done

rem --- Summary ---------------------------------------------------------------
echo.
echo ============================================
echo   Startup summary
echo ============================================
echo   Seating system  : http://localhost:8080   (admin / admin)     [%APP_STATUS%]
echo   Student login   : http://localhost:8080   (roll no / roll no) [%APP_STATUS%]
echo   Notifications   : http://localhost:3000   (subscribe page)    [%NOTIFY_STATUS%]
echo   Notify admin    : http://localhost:3000/admin-login.html      [%NOTIFY_STATUS%]
echo.
echo   MySQL           : localhost:3306 [%DB_STATUS%]
echo   Stop a server   : close its window or press Ctrl+C inside it
echo   Stop MySQL      : docker compose stop   (run inside "java project")
echo.
if "%APP_READY%"=="1" start "" "http://localhost:8080"
if "%NOTIFY_READY%"=="1" start "" "http://localhost:3000"
pause
endlocal

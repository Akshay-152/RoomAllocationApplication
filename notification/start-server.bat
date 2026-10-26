@echo off
setlocal

rem Use an absolute entry path so CLOS.BAT can identify only this project server.
node "%~dp0src\server.js"
exit /b %ERRORLEVEL%

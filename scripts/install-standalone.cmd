@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-standalone.ps1" %*
exit /b %ERRORLEVEL%

@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-netbeans.ps1" %*
exit /b %ERRORLEVEL%

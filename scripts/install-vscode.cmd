@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-vscode.ps1" %*
exit /b %ERRORLEVEL%

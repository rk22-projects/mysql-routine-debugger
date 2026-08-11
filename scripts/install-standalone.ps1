[CmdletBinding()]
param([switch]$SkipToolInstall)

. "$PSScriptRoot\lib\Bootstrap.ps1"
$repo = Get-RepoRoot $PSScriptRoot
$java = Ensure-Java17 (-not $SkipToolInstall)

Write-Host 'Building the standalone JavaFX application...' -ForegroundColor Cyan
Invoke-MavenWrapper $repo @('-pl', 'standalone', '-am', 'clean', 'package')

$release = Join-Path $repo 'release\standalone'
Reset-Directory $release
$jar = Join-Path $repo 'standalone\target\proc-debugger-standalone.jar'
if (-not (Test-Path $jar)) { throw "Standalone artifact was not produced: $jar" }
Copy-Item -LiteralPath $jar -Destination $release

$launcher = @"
@echo off
"$java" -jar "%~dp0proc-debugger-standalone.jar"
"@
Set-Content -LiteralPath (Join-Path $release 'MariaDB Procedure Debugger.cmd') -Value $launcher -Encoding Ascii

Write-Host "Installed standalone application in: $release" -ForegroundColor Green
Write-Host 'Run "MariaDB Procedure Debugger.cmd" from that directory.'

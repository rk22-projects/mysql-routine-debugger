[CmdletBinding()]
param([switch]$SkipToolInstall)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Invoke-External([string]$FilePath, [string[]]$Arguments) {
    Write-Host "> $FilePath $($Arguments -join ' ')" -ForegroundColor DarkGray
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code ${LASTEXITCODE}: $FilePath" }
}

function Install-WingetPackage([string]$Id, [string]$DisplayName) {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $winget) { throw "$DisplayName is missing and winget is unavailable." }
    Write-Host "Installing $DisplayName with winget..." -ForegroundColor Cyan
    Invoke-External $winget.Source @('install', '--exact', '--id', $Id, '--silent', '--accept-package-agreements', '--accept-source-agreements')
}

function Get-JavaMajor([string]$Executable) {
    try {
        $info = [System.Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $Executable
        $info.Arguments = '-version'
        $info.UseShellExecute = $false
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $process = [System.Diagnostics.Process]::Start($info)
        $text = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($text -match 'version\s+"(?<major>\d+)') { return [int]$Matches.major }
    } catch {}
    return 0
}

function Find-Java17 {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    $pathJava = Get-Command java -ErrorAction SilentlyContinue
    if ($pathJava) { $candidates.Add($pathJava.Source) }
    foreach ($root in @('Java', 'Eclipse Adoptium', 'Microsoft', 'Amazon Corretto')) {
        $folder = Join-Path ${env:ProgramFiles} $root
        if (Test-Path $folder) {
            Get-ChildItem $folder -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $candidates.Add((Join-Path $_.FullName 'bin\java.exe'))
            }
        }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ((Test-Path $candidate) -and (Get-JavaMajor $candidate) -ge 17) { return (Resolve-Path $candidate).Path }
    }
    return $null
}

function Ensure-Java17 {
    $javaPath = Find-Java17
    if (-not $javaPath -and -not $SkipToolInstall) {
        Install-WingetPackage 'EclipseAdoptium.Temurin.17.JDK' 'Eclipse Temurin JDK 17'
        $javaPath = Find-Java17
    }
    if (-not $javaPath) { throw 'Java 17+ was not found. Install it or omit -SkipToolInstall.' }
    $env:JAVA_HOME = Split-Path (Split-Path $javaPath -Parent) -Parent
    $env:Path = "$(Split-Path $javaPath -Parent);$env:Path"
    Write-Host "Using Java $(Get-JavaMajor $javaPath): $javaPath" -ForegroundColor Green
    return $javaPath
}

function Reset-ReleaseDirectory([string]$Path) {
    if (Test-Path $Path) {
        $resolved = (Resolve-Path $Path).Path
        if (-not $resolved.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe output path: $resolved" }
        Remove-Item $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
}

$java = Ensure-Java17

Write-Host 'Building the standalone JavaFX application...' -ForegroundColor Cyan
Invoke-External (Join-Path $repo 'mvnw.cmd') @('-pl', 'standalone', '-am', 'clean', 'package')

$release = Join-Path $repo 'release\standalone'
Reset-ReleaseDirectory $release
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

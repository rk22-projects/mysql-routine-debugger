[CmdletBinding()]
param(
    [switch]$SkipToolInstall,
    [switch]$SkipExtensionInstall
)

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
    $java = Find-Java17
    if (-not $java -and -not $SkipToolInstall) {
        Install-WingetPackage 'EclipseAdoptium.Temurin.17.JDK' 'Eclipse Temurin JDK 17'
        $java = Find-Java17
    }
    if (-not $java) { throw 'Java 17+ was not found. Install it or omit -SkipToolInstall.' }
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
    $env:Path = "$(Split-Path $java -Parent);$env:Path"
    Write-Host "Using Java $(Get-JavaMajor $java): $java" -ForegroundColor Green
}

function Ensure-Node {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node -and -not $SkipToolInstall) {
        Install-WingetPackage 'OpenJS.NodeJS.LTS' 'Node.js LTS'
        $known = Join-Path ${env:ProgramFiles} 'nodejs\node.exe'
        if (Test-Path $known) { $env:Path = "$(Split-Path $known -Parent);$env:Path" }
        $node = Get-Command node -ErrorAction SilentlyContinue
    }
    if (-not $node) { throw 'Node.js was not found. Install it or omit -SkipToolInstall.' }
    Write-Host "Using Node.js: $($node.Source)" -ForegroundColor Green
}

function Find-VSCode {
    $code = Get-Command code -ErrorAction SilentlyContinue
    if ($code) { return $code.Source }
    foreach ($candidate in @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Microsoft VS Code\bin\code.cmd'),
        (Join-Path ${env:ProgramFiles} 'Microsoft VS Code\bin\code.cmd')
    )) {
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Ensure-VSCode {
    $code = Find-VSCode
    if (-not $code -and -not $SkipToolInstall) {
        Install-WingetPackage 'Microsoft.VisualStudioCode' 'Visual Studio Code'
        $code = Find-VSCode
    }
    if (-not $code) { throw 'Visual Studio Code was not found. Install it or use -SkipExtensionInstall.' }
    return $code
}

function Reset-ReleaseDirectory([string]$Path) {
    if (Test-Path $Path) {
        $resolved = (Resolve-Path $Path).Path
        if (-not $resolved.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe output path: $resolved" }
        Remove-Item $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
}

Ensure-Java17
Ensure-Node

Write-Host 'Building the shared core and VS Code bridge...' -ForegroundColor Cyan
Invoke-External (Join-Path $repo 'mvnw.cmd') @('-pl', 'vscode', '-am', 'clean', 'package')

$npm = Join-Path ${env:ProgramFiles} 'nodejs\npm.cmd'
if (-not (Test-Path $npm)) {
    $npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npmCommand) { throw 'npm.cmd was not found after locating Node.js.' }
    $npm = $npmCommand.Source
}
Write-Host 'Packaging the VS Code extension...' -ForegroundColor Cyan
Push-Location (Join-Path $repo 'vscode')
try { Invoke-External $npm @('run', 'package') } finally { Pop-Location }

$version = (Get-Content (Join-Path $repo 'vscode\package.json') -Raw | ConvertFrom-Json).version
$vsix = Join-Path $repo "vscode\mariadb-procedure-debugger-$version.vsix"
if (-not (Test-Path $vsix)) { throw "VSIX was not produced: $vsix" }
$release = Join-Path $repo 'release\vscode'
Reset-ReleaseDirectory $release
Copy-Item -LiteralPath $vsix -Destination $release

if (-not $SkipExtensionInstall) {
    $code = Ensure-VSCode
    Write-Host 'Installing the extension into Visual Studio Code...' -ForegroundColor Cyan
    Invoke-External $code @('--install-extension', $vsix, '--force')
}

Write-Host "VS Code extension package: $vsix" -ForegroundColor Green
if ($SkipExtensionInstall) { Write-Host 'Extension installation was skipped as requested.' }
else { Write-Host 'Reload Visual Studio Code before opening MariaDB Procedure Debugger.' }

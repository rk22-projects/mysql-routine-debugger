Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-RepoRoot {
    param([Parameter(Mandatory)][string]$ScriptDirectory)
    return (Resolve-Path (Join-Path $ScriptDirectory '..')).Path
}

function Invoke-External {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter()][string[]]$Arguments = @()
    )
    Write-Host "> $FilePath $($Arguments -join ' ')" -ForegroundColor DarkGray
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
}

function Install-WingetPackage {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$DisplayName
    )
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $winget) {
        throw "$DisplayName is missing and Windows Package Manager (winget) is unavailable. Install App Installer from Microsoft, then rerun this script."
    }
    Write-Host "Installing $DisplayName with winget..." -ForegroundColor Cyan
    Invoke-External $winget.Source @(
        'install', '--exact', '--id', $Id, '--silent',
        '--accept-package-agreements', '--accept-source-agreements'
    )
}

function Get-JavaMajor {
    param([Parameter(Mandatory)][string]$Executable)
    try {
        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $Executable
        $startInfo.Arguments = '-version'
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = [System.Diagnostics.Process]::Start($startInfo)
        $text = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($text -match 'version\s+"(?<first>\d+)(?:\.(?<second>\d+))?') {
            if ([int]$Matches.first -eq 1) { return [int]$Matches.second }
            return [int]$Matches.first
        }
    } catch {}
    return 0
}

function Find-Java17 {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    $pathJava = Get-Command java -ErrorAction SilentlyContinue
    if ($pathJava) { $candidates.Add($pathJava.Source) }

    $programFiles = ${env:ProgramFiles}
    foreach ($root in @(
        (Join-Path $programFiles 'Java'),
        (Join-Path $programFiles 'Eclipse Adoptium'),
        (Join-Path $programFiles 'Microsoft'),
        (Join-Path $programFiles 'Amazon Corretto')
    )) {
        if (Test-Path $root) {
            Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $candidates.Add((Join-Path $_.FullName 'bin\java.exe'))
            }
        }
    }
    $candidates.Add((Join-Path $programFiles 'Apache NetBeans\jdk\bin\java.exe'))

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ((Test-Path -LiteralPath $candidate) -and (Get-JavaMajor $candidate) -ge 17) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Ensure-Java17 {
    param([bool]$InstallTools = $true)
    $java = Find-Java17
    if (-not $java -and $InstallTools) {
        Install-WingetPackage 'EclipseAdoptium.Temurin.17.JDK' 'Eclipse Temurin JDK 17'
        $java = Find-Java17
    }
    if (-not $java) {
        throw 'Java 17+ was not found. Install a JDK 17 or rerun without -SkipToolInstall.'
    }
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
    $env:Path = "$(Split-Path $java -Parent);$env:Path"
    Write-Host "Using Java $(Get-JavaMajor $java): $java" -ForegroundColor Green
    return $java
}

function Ensure-Node {
    param([bool]$InstallTools = $true)
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) {
        $known = Join-Path ${env:ProgramFiles} 'nodejs\node.exe'
        if (Test-Path $known) { $node = Get-Item $known }
    }
    if (-not $node -and $InstallTools) {
        Install-WingetPackage 'OpenJS.NodeJS.LTS' 'Node.js LTS'
        $known = Join-Path ${env:ProgramFiles} 'nodejs\node.exe'
        if (Test-Path $known) {
            $env:Path = "$(Split-Path $known -Parent);$env:Path"
            $node = Get-Item $known
        }
    }
    if (-not $node) { throw 'Node.js LTS was not found. Install Node.js or rerun without -SkipToolInstall.' }
    $nodePath = if ($node -is [System.Management.Automation.CommandInfo]) { $node.Source } else { $node.FullName }
    Write-Host "Using Node.js: $nodePath" -ForegroundColor Green
    return $nodePath
}

function Ensure-VSCode {
    param([bool]$InstallTools = $true)
    $code = Get-Command code -ErrorAction SilentlyContinue
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Microsoft VS Code\bin\code.cmd'),
        (Join-Path ${env:ProgramFiles} 'Microsoft VS Code\bin\code.cmd')
    )
    if (-not $code) {
        foreach ($candidate in $candidates) {
            if (Test-Path $candidate) { $code = Get-Item $candidate; break }
        }
    }
    if (-not $code -and $InstallTools) {
        Install-WingetPackage 'Microsoft.VisualStudioCode' 'Visual Studio Code'
        foreach ($candidate in $candidates) {
            if (Test-Path $candidate) { $code = Get-Item $candidate; break }
        }
    }
    if (-not $code) { throw 'Visual Studio Code was not found. Install it or use -SkipExtensionInstall.' }
    if ($code -is [System.Management.Automation.CommandInfo]) { return $code.Source }
    return $code.FullName
}

function Invoke-MavenWrapper {
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][string[]]$Arguments
    )
    $wrapper = Join-Path $RepoRoot 'mvnw.cmd'
    if (-not (Test-Path $wrapper)) { throw "Maven Wrapper is missing: $wrapper" }
    Invoke-External $wrapper $Arguments
}

function Reset-Directory {
    param([Parameter(Mandatory)][string]$Path)
    if (Test-Path -LiteralPath $Path) {
        $resolved = (Resolve-Path -LiteralPath $Path).Path
        if (-not $resolved.StartsWith((Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to replace a directory outside the repository: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
}

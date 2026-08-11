[CmdletBinding()]
param(
    [switch]$SkipToolInstall,
    [string]$NetBeansHome,
    [string]$NetBeansUserDir
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

function Invoke-MavenWrapper([string[]]$Arguments) {
    Invoke-External (Join-Path $repo 'mvnw.cmd') $Arguments
}

function Reset-Directory([string]$Path) {
    if (Test-Path $Path) {
        $resolved = (Resolve-Path $Path).Path
        if (-not $resolved.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe output path: $resolved" }
        Remove-Item $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
}

function Get-NetBeansMajor([string]$Path) {
    if (-not $Path) { return 0 }
    $coreJar = Join-Path $Path 'platform\core\core.jar'
    if (-not (Test-Path -LiteralPath $coreJar)) { return 0 }
    $zip = $null
    $reader = $null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($coreJar)
        $manifest = $zip.GetEntry('META-INF/MANIFEST.MF')
        if (-not $manifest) { return 0 }
        $reader = [System.IO.StreamReader]::new($manifest.Open())
        $text = $reader.ReadToEnd()
        if ($text -match 'OpenIDE-Module-Implementation-Version:\s*(\d+)-') {
            return [int]$Matches[1]
        }
    } catch {
        return 0
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($zip) { $zip.Dispose() }
    }
    return 0
}

function Test-NetBeansHome([string]$Path) {
    return $Path -and
        (Get-NetBeansMajor $Path) -gt 0 -and
        (Test-Path (Join-Path $Path 'ide\modules\org-netbeans-modules-db.jar')) -and
        (Test-Path (Join-Path $Path 'bin\netbeans64.exe'))
}

if (-not (Test-NetBeansHome $NetBeansHome)) {
    $candidates = [System.Collections.Generic.List[string]]::new()
    @(
        $env:NETBEANS_HOME,
        (Join-Path ${env:ProgramFiles} 'Apache NetBeans')
    ) | Where-Object { $_ } | ForEach-Object { $candidates.Add($_) }
    foreach ($root in @(${env:ProgramFiles}, (Join-Path $repo '.tools'))) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like '*NetBeans*' -or $_.Name -like 'netbeans-*' } |
                ForEach-Object {
                    $candidates.Add($_.FullName)
                    $candidates.Add((Join-Path $_.FullName 'netbeans'))
                }
        }
    }
    $NetBeansHome = $candidates | Where-Object { Test-NetBeansHome $_ } | Select-Object -First 1
}

if (-not (Test-NetBeansHome $NetBeansHome)) {
    throw 'Apache NetBeans is a prerequisite and was not found. Install it locally or pass -NetBeansHome.'
}
$netBeansMajor = Get-NetBeansMajor $NetBeansHome
$netBeansRelease = "RELEASE${netBeansMajor}0"
if (-not $NetBeansUserDir) {
    $NetBeansUserDir = Join-Path $env:APPDATA "NetBeans\$netBeansMajor"
}
Write-Host "Using Apache NetBeans ${netBeansMajor}: $NetBeansHome" -ForegroundColor Green
Ensure-Java17

$dbModule = Join-Path $NetBeansHome 'ide\modules\org-netbeans-modules-db.jar'
Write-Host 'Registering the NetBeans DB Explorer API in the local Maven repository...' -ForegroundColor Cyan
Invoke-MavenWrapper @(
    '-N', 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
    "-Dfile=$dbModule", '-DgroupId=org.netbeans.modules',
    '-DartifactId=org-netbeans-modules-db', "-Dversion=$netBeansRelease",
    '-Dpackaging=jar', '-DgeneratePom=true'
)

Write-Host 'Building the NetBeans plugin...' -ForegroundColor Cyan
Invoke-MavenWrapper @("-Dnb.version=$netBeansRelease", '-pl', 'plugin', '-am', 'clean', 'package')
$nbm = Join-Path $repo 'plugin\target\proc-debugger-nb-1.0-SNAPSHOT.nbm'
if (-not (Test-Path $nbm)) { throw "NetBeans module was not produced: $nbm" }

$release = Join-Path $repo 'release\netbeans'
Reset-Directory $release
Copy-Item -LiteralPath $nbm -Destination $release

Write-Host "Installing the module into NetBeans user directory: $NetBeansUserDir" -ForegroundColor Cyan
New-Item -ItemType Directory -Path $NetBeansUserDir -Force | Out-Null
$extract = Join-Path $repo '.tools\nbm-extract'
Reset-Directory $extract
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($nbm, $extract)
Copy-Item -Path (Join-Path $extract 'netbeans\*') -Destination $NetBeansUserDir -Recurse -Force

Write-Host "Installed NetBeans plugin: $nbm" -ForegroundColor Green
Write-Host "Restart NetBeans $netBeansMajor. The debugger appears under Window > MariaDB Procedure Debugger and in the database explorer routine actions."

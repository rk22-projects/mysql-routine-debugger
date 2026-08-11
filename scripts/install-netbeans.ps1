[CmdletBinding()]
param(
    [switch]$SkipToolInstall,
    [string]$NetBeansHome,
    [string]$NetBeansUserDir = (Join-Path $env:APPDATA 'NetBeans\27')
)

. "$PSScriptRoot\lib\Bootstrap.ps1"
$repo = Get-RepoRoot $PSScriptRoot
Ensure-Java17 (-not $SkipToolInstall) | Out-Null

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
        (Get-NetBeansMajor $Path) -eq 27 -and
        (Test-Path (Join-Path $Path 'ide\modules\org-netbeans-modules-db.jar')) -and
        (Test-Path (Join-Path $Path 'bin\netbeans64.exe'))
}

if (-not (Test-NetBeansHome $NetBeansHome)) {
    $candidates = @(
        $env:NETBEANS_HOME,
        (Join-Path ${env:ProgramFiles} 'Apache NetBeans'),
        (Join-Path $repo '.tools\netbeans-27\netbeans')
    )
    $NetBeansHome = $candidates | Where-Object { Test-NetBeansHome $_ } | Select-Object -First 1
}

if (-not $NetBeansHome -and -not $SkipToolInstall) {
    $tools = Join-Path $repo '.tools'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    $zip = Join-Path $tools 'netbeans-27-bin.zip'
    $installRoot = Join-Path $tools 'netbeans-27'
    $baseUrl = 'https://archive.apache.org/dist/netbeans/netbeans/27/netbeans-27-bin.zip'

    Write-Host 'Downloading Apache NetBeans 27 (approximately 491 MB)...' -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing -Uri $baseUrl -OutFile $zip
    $checksumText = (Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl.sha512").Content.Trim()
    $expected = ($checksumText -split '\s+')[0].ToUpperInvariant()
    $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA512).Hash.ToUpperInvariant()
    if ($actual -ne $expected) { throw 'Apache NetBeans download checksum verification failed.' }

    Reset-Directory $installRoot
    Expand-Archive -LiteralPath $zip -DestinationPath $installRoot
    $NetBeansHome = Join-Path $installRoot 'netbeans'
}

if (-not (Test-NetBeansHome $NetBeansHome)) {
    throw 'Apache NetBeans 27 was not found. Pass -NetBeansHome or rerun without -SkipToolInstall.'
}
Write-Host "Using Apache NetBeans 27: $NetBeansHome" -ForegroundColor Green

$dbModule = Join-Path $NetBeansHome 'ide\modules\org-netbeans-modules-db.jar'
Write-Host 'Registering the NetBeans DB Explorer API in the local Maven repository...' -ForegroundColor Cyan
Invoke-MavenWrapper $repo @(
    '-N', 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
    "-Dfile=$dbModule", '-DgroupId=org.netbeans.modules',
    '-DartifactId=org-netbeans-modules-db', '-Dversion=RELEASE270',
    '-Dpackaging=jar', '-DgeneratePom=true'
)

Write-Host 'Building the NetBeans plugin...' -ForegroundColor Cyan
Invoke-MavenWrapper $repo @('-pl', 'plugin', '-am', 'clean', 'package')
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
Write-Host 'Restart NetBeans 27. The debugger appears under Window > MariaDB Procedure Debugger and in the database explorer routine actions.'

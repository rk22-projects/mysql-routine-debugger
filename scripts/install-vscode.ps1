[CmdletBinding()]
param(
    [switch]$SkipToolInstall,
    [switch]$SkipExtensionInstall
)

. "$PSScriptRoot\lib\Bootstrap.ps1"
$repo = Get-RepoRoot $PSScriptRoot
Ensure-Java17 (-not $SkipToolInstall) | Out-Null
Ensure-Node (-not $SkipToolInstall) | Out-Null

Write-Host 'Building the shared core and VS Code bridge...' -ForegroundColor Cyan
Invoke-MavenWrapper $repo @('-pl', 'vscode', '-am', 'clean', 'package')

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
Reset-Directory $release
Copy-Item -LiteralPath $vsix -Destination $release

if (-not $SkipExtensionInstall) {
    $code = Ensure-VSCode (-not $SkipToolInstall)
    Write-Host 'Installing the extension into Visual Studio Code...' -ForegroundColor Cyan
    Invoke-External $code @('--install-extension', $vsix, '--force')
}

Write-Host "VS Code extension package: $vsix" -ForegroundColor Green
if ($SkipExtensionInstall) { Write-Host 'Extension installation was skipped as requested.' }
else { Write-Host 'Reload Visual Studio Code before opening MariaDB Procedure Debugger.' }

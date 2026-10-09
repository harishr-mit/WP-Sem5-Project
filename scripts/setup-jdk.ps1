# Downloads a project-local Eclipse Temurin JDK 21 into .tools/jdk-21 (gitignored).
# Nothing outside the repo is touched: no PATH, registry or JAVA_HOME changes.
# Usage (from the repo root):  powershell -ExecutionPolicy Bypass -File scripts\setup-jdk.ps1
# Afterwards, point the current shell at it:  . .\scripts\env.ps1   (Git Bash: source scripts/env.sh)
# VS Code terminals and the Java extension pick it up through .vscode/settings.json.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # PowerShell 5.1 downloads crawl with the progress bar on
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Pinned release; bump all three together (values from api.adoptium.net/v3/assets/latest/21/hotspot).
$Release = 'jdk-21.0.12.1+1'
$Url     = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip'
$Sha256  = 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'

$Root   = Split-Path -Parent $PSScriptRoot
$Tools  = Join-Path $Root '.tools'
$Target = Join-Path $Tools 'jdk-21'
$Marker = Join-Path $Target 'release.mitpass'

if ((Test-Path $Marker) -and ((Get-Content $Marker -Raw).Trim() -eq $Release)) {
    Write-Host "JDK $Release already installed at $Target"
    exit 0
}

New-Item -ItemType Directory -Force $Tools | Out-Null
$Zip     = Join-Path $Tools 'jdk-21.zip'
$Staging = Join-Path $Tools 'jdk-21.staging'

Write-Host "Downloading Temurin $Release ..."
Invoke-WebRequest -Uri $Url -OutFile $Zip -UseBasicParsing

$Actual = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower()
if ($Actual -ne $Sha256) {
    Remove-Item $Zip -Force
    throw "Checksum mismatch for $Url`n  expected $Sha256`n  actual   $Actual"
}

Write-Host 'Checksum OK, extracting ...'
if (Test-Path $Staging) { Remove-Item $Staging -Recurse -Force }
New-Item -ItemType Directory -Force $Staging | Out-Null
$Tar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (Test-Path $Tar) {
    & $Tar -xf $Zip -C $Staging
    if ($LASTEXITCODE -ne 0) { throw "tar failed with exit code $LASTEXITCODE" }
} else {
    Expand-Archive -Path $Zip -DestinationPath $Staging
}

if (Test-Path $Target) { Remove-Item $Target -Recurse -Force }
Move-Item (Join-Path $Staging $Release) $Target
Set-Content -Path $Marker -Value $Release -Encoding ASCII
Remove-Item $Staging -Recurse -Force
Remove-Item $Zip -Force

& (Join-Path $Target 'bin\java.exe') -version
Write-Host "JDK installed at $Target"

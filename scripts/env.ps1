# Points the current PowerShell session at the project-local JDK 21.
# Usage (from the repo root):  . .\scripts\env.ps1
$Jdk = Join-Path (Split-Path -Parent $PSScriptRoot) '.tools\jdk-21'
if (-not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) {
    Write-Warning "No JDK at $Jdk. Run: powershell -ExecutionPolicy Bypass -File scripts\setup-jdk.ps1"
    return
}
$env:JAVA_HOME = $Jdk
if (-not ($env:Path -split ';' -contains "$Jdk\bin")) { $env:Path = "$Jdk\bin;$env:Path" }
Write-Host "JAVA_HOME=$env:JAVA_HOME"

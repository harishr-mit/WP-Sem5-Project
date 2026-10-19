# Makes the portal's self-signed HTTPS certificate for phone demos (docs/DECISIONS.md D15).
# Phone cameras only work on secure pages, so the portal's "lan" profile serves https://<laptop-ip>:8443 with this.
# Uses the project JDK's keytool: nothing is installed and no trust store is changed. Each phone accepts the warning once.
# The laptop's current LAN addresses go into the certificate, so run it again on a new network.
#
#   powershell -ExecutionPolicy Bypass -File scripts\make-dev-cert.ps1
#
param(
    [string]$Password = $(if ($env:MITPASS_PORTAL_CERT_PASSWORD) { $env:MITPASS_PORTAL_CERT_PASSWORD } else { 'dev-only-changeit' })
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$keytool = Join-Path $root '.tools\jdk-21\bin\keytool.exe'
if (-not (Test-Path $keytool)) {
    throw "Project JDK not found at $keytool. Run scripts\setup-jdk.ps1 first."
}
$certs = Join-Path $root 'infra\certs'
New-Item -ItemType Directory -Force $certs | Out-Null
$store = Join-Path $certs 'portal-dev.p12'

# localhost plus every IPv4 address of a connected adapter (not loopback, not link-local)
$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    Select-Object -ExpandProperty IPAddress
$san = @('dns:localhost', 'ip:127.0.0.1') + ($ips | ForEach-Object { "ip:$_" })

if (Test-Path $store) { Remove-Item $store -Force -Confirm:$false }
& $keytool -genkeypair -alias portal -keyalg RSA -keysize 2048 -validity 365 `
    -storetype PKCS12 -keystore $store -storepass $Password -keypass $Password `
    -dname 'CN=MITPass dev portal, O=MITPass (dev only)' -ext ("SAN=" + ($san -join ',')) | Out-Null
if ($LASTEXITCODE -ne 0) { throw "keytool failed ($LASTEXITCODE)" }

Write-Host "Wrote $store"
Write-Host "Valid for: $($san -join ', ')"
Write-Host ''
Write-Host 'Start the portal with HTTPS (from the repo root):'
Write-Host '  .\mvnw.cmd -pl web-portal spring-boot:run "-Dspring-boot.run.profiles=lan"'
foreach ($ip in $ips) { Write-Host "Then open https://${ip}:8443 on a phone on the same Wi-Fi (accept the warning once)." }

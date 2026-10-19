# Pre-flight check for the two-phone demo (README "Phone demo"). Read-only: it changes nothing, needs no admin.
# Run from the repo root once the portal is started with the lan profile:
#   powershell -ExecutionPolicy Bypass -File scripts\phone-demo-check.ps1
# Each line says OK or FIX; a FIX line names what to do. At the end it prints the address to open on the phones.

param([int]$Port = 8443)

$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$port = $Port
$cert = Join-Path $root 'infra\certs\portal-dev.p12'
$keytool = Join-Path $root '.tools\jdk-21\bin\keytool.exe'
$java = Join-Path $root '.tools\jdk-21\bin\java.exe'
$hotspotRule = 'MITPass portal (HTTPS 8443, laptop hotspot)'
$privateRule = 'MITPass portal (HTTPS 8443)'
$fixes = 0

function Ok($text) { Write-Host "  OK   $text" -ForegroundColor Green }
function Fix($text) { Write-Host "  FIX  $text" -ForegroundColor Yellow; $script:fixes++ }

# 1. Which address will the phones use? The laptop's own hotspot (192.168.137.x) is the reliable one.
Write-Host "Network"
$hotspot = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like '192.168.137.*' } | Select-Object -First 1
$lan = Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
    $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -notlike '192.168.137.*' -and
    $_.InterfaceAlias -notlike 'vEthernet*' }
if ($hotspot) {
    $address = $hotspot.IPAddress
    Ok "laptop hotspot is on: $address ($($hotspot.InterfaceAlias))"
    $clients = Get-NetNeighbor -AddressFamily IPv4 -InterfaceAlias $hotspot.InterfaceAlias |
        Where-Object { $_.IPAddress -like '192.168.137.*' -and $_.IPAddress -ne '192.168.137.255' -and $_.State -ne 'Unreachable' }
    if ($clients) { Ok "devices on the hotspot: $(($clients.IPAddress | Sort-Object -Unique) -join ', ')" }
    else { Fix "no phone on the hotspot yet: connect the phones to the laptop's hotspot (and turn their mobile data off)" }
} elseif ($lan) {
    $address = ($lan | Select-Object -First 1).IPAddress
    Fix "laptop hotspot is off: phones would use $address on a shared network, where routers and phone hotspots often block devices from each other. Turn on Settings > Network & internet > Mobile hotspot"
} else {
    $address = $null
    Fix "no network address: connect the laptop to Wi-Fi and turn on Mobile hotspot"
}

# 2. The certificate must name that address, or the phone refuses it even after the warning.
Write-Host "Certificate"
if (-not (Test-Path $cert)) {
    Fix "no certificate: powershell -ExecutionPolicy Bypass -File scripts\make-dev-cert.ps1 (with the hotspot on)"
} else {
    $sans = & $keytool -list -v -keystore $cert -storetype PKCS12 -storepass 'dev-only-changeit' 2>$null |
        Select-String 'IPAddress: ' | ForEach-Object { ($_ -split 'IPAddress: ')[1].Trim() }
    if ($address -and ($sans -contains $address)) { Ok "certificate names $address" }
    elseif ($address) { Fix "certificate names $($sans -join ', ') but not $address`: remake it with scripts\make-dev-cert.ps1, then restart the portal" }
}

# 3. The portal must run with the lan profile (https on 8443), not the plain http :8090 run.
Write-Host "Portal"
$listen = Get-NetTCPConnection -State Listen -LocalPort $port
if ($listen) {
    Ok "something listens on $port"
    $code = & curl.exe -k -s -o NUL -w '%{http_code}' --max-time 5 "https://localhost:$port/login"
    if ($code -eq '200') { Ok "https://localhost:$port/login answers 200" }
    else { Fix "https://localhost:$port/login answered '$code': check the portal's log (the gateway and services must be running)" }
} else {
    Fix "nothing on $port`: from the repo root run .tools\jdk-21\bin\java -jar web-portal\target\web-portal.jar --spring.profiles.active=lan (not mvnw spring-boot:run)"
}

# 4. Windows Firewall: the port rules, and no other rule for the project's Java. The "Allow access?" prompt leaves
#    either a Block rule (beats every Allow rule: the phones time out) or an Allow for every port (Eureka, services).
Write-Host "Firewall"
$javaRules = Get-NetFirewallApplicationFilter | Where-Object { $_.Program -eq $java } | Get-NetFirewallRule
$blocks = $javaRules | Where-Object Action -eq 'Block'
$allPorts = $javaRules | Where-Object { $_.Action -eq 'Allow' -and (($_ | Get-NetFirewallPortFilter).LocalPort -contains 'Any') }
if ($blocks) { Fix "$(@($blocks).Count) Block rule(s) for the project's Java: run step 1 of README 'Phone demo' (administrator)" }
else { Ok "no Block rule for the project's Java" }
if ($allPorts) { Fix "$(@($allPorts).Count) rule(s) open every port of the project's Java ($((@($allPorts) | ForEach-Object { $_.Profile }) -join ', ')): run step 1 of README 'Phone demo' (administrator)" }
else { Ok "no rule opens every port of the project's Java" }
if ($hotspot) {
    if (Get-NetFirewallRule -DisplayName $hotspotRule) { Ok "rule '$hotspotRule' exists" }
    else { Fix "missing rule '$hotspotRule' (the hotspot counts as a Public network): run step 1 of README 'Phone demo' (administrator)" }
} elseif (-not (Get-NetFirewallRule -DisplayName $privateRule)) {
    Fix "missing rule '$privateRule': run step 1 of README 'Phone demo' (administrator)"
}

Write-Host ""
if ($fixes -eq 0 -and $address) {
    Write-Host "Ready. On each phone open  https://$address`:$port  and accept the certificate warning once." -ForegroundColor Green
} else {
    Write-Host "$fixes thing(s) to fix first (see FIX lines)." -ForegroundColor Yellow
}
exit $fixes

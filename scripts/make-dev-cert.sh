#!/usr/bin/env sh
# Makes the portal's self-signed HTTPS certificate for phone demos (docs/DECISIONS.md D15).
# Phone cameras only work on secure pages, so the portal's "lan" profile serves https://<laptop-ip>:8443 with this.
# Uses a JDK's keytool (the project JDK if present, else JAVA_HOME): nothing is installed, no trust store is changed.
# The laptop's current LAN addresses go into the certificate, so run it again on a new network.
#
#   sh scripts/make-dev-cert.sh
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
password=${MITPASS_PORTAL_CERT_PASSWORD:-dev-only-changeit}

if [ -x "$root/.tools/jdk-21/bin/keytool" ]; then keytool="$root/.tools/jdk-21/bin/keytool"
elif [ -x "$root/.tools/jdk-21/bin/keytool.exe" ]; then keytool="$root/.tools/jdk-21/bin/keytool.exe"
elif [ -n "${JAVA_HOME:-}" ]; then keytool="$JAVA_HOME/bin/keytool"
else echo "No JDK found: run scripts/setup-jdk.ps1 (Windows) or set JAVA_HOME" >&2; exit 1
fi

mkdir -p "$root/infra/certs"
store="$root/infra/certs/portal-dev.p12"

# IPv4 addresses of this machine (Linux: hostname -I; macOS: ifconfig; Git Bash: ipconfig), minus loopback/link-local
# All three are tried (Windows' hostname -I "succeeds" with no addresses); the filter keeps real IPv4 addresses.
ips=$( { hostname -I 2>/dev/null; ifconfig 2>/dev/null | awk '/inet /{print $2}'; ipconfig 2>/dev/null | awk -F': ' '/IPv4/{print $2}'; } \
    | tr ' \r' '\n\n' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | grep -v -E '^(127\.|169\.254\.)' | sort -u || true)
san="dns:localhost,ip:127.0.0.1"
for ip in $ips; do san="$san,ip:$ip"; done

rm -f "$store"
"$keytool" -genkeypair -alias portal -keyalg RSA -keysize 2048 -validity 365 \
    -storetype PKCS12 -keystore "$store" -storepass "$password" -keypass "$password" \
    -dname "CN=MITPass dev portal, O=MITPass (dev only)" -ext "SAN=$san" >/dev/null

echo "Wrote $store"
echo "Valid for: $san"
echo
echo "Start the portal with HTTPS (from the repo root):"
echo "  ./mvnw -pl web-portal spring-boot:run -Dspring-boot.run.profiles=lan"
for ip in $ips; do echo "Then open https://$ip:8443 on a phone on the same Wi-Fi (accept the warning once)."; done

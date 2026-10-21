#!/usr/bin/env bash
# Stops what scripts/run-all.sh started: every java process listening on a MITPass port (Windows and Linux).
# Infrastructure stays up; stop it with: docker compose -f infra/docker-compose.yml down
ports="8761 8080 8081 8082 8083 8084 8085 8095 8090 8443"
if command -v powershell.exe > /dev/null; then
    powershell.exe -NoProfile -Command "foreach (\$p in '$ports'.Split(' ')) { Get-NetTCPConnection -LocalPort \$p -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force; \"stopped :\$p\" } }"
else
    for p in $ports; do pid=$(lsof -ti tcp:"$p" -sTCP:LISTEN || true); [ -n "$pid" ] && kill "$pid" && echo "stopped :$p"; done
fi

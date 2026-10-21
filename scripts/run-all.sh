#!/usr/bin/env bash
# Starts every MITPass service from its built jar (./mvnw install -DskipTests first), in the README's order, with
# logs in ./logs/<service>.log. Infrastructure first: docker compose -f infra/docker-compose.yml up -d --wait
# Extra arguments go to notification-service (e.g. --mitpass.notifications.reminders.interval=10s).
# Stop them with scripts/stop-all.sh (or Ctrl+C in each window if you start them by hand).
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA=".tools/jdk-21/bin/java"
[ -x "$JAVA" ] || JAVA=".tools/jdk-21/bin/java.exe"
mkdir -p logs
start() { # <name> <jar> [args...]
    local name="$1" jar="$2"; shift 2
    nohup "$JAVA" -jar "$jar" "$@" > "logs/$name.log" 2>&1 &
    echo "started $name (pid $!), log: logs/$name.log"
}
wait_for() { # <url>
    curl -s -o /dev/null --retry 60 --retry-all-errors --retry-delay 2 "$1" || echo "  (no answer from $1 yet)"
}
start discovery-server platform/discovery-server/target/discovery-server.jar
wait_for http://localhost:8761/actuator/health
start auth-service services/auth-service/target/auth-service.jar
start event-service services/event-service/target/event-service.jar
start pass-service services/pass-service/target/pass-service.jar
start checkin-service services/checkin-service/target/checkin-service.jar
start notification-service services/notification-service/target/notification-service.jar "$@"
start api-gateway platform/api-gateway/target/api-gateway.jar
start web-portal web-portal/target/web-portal.jar
for port in 8081 8082 8083 8084 8085 8080 8090; do wait_for "http://localhost:$port/actuator/health"; done
echo "all up: portal http://localhost:8090, gateway http://localhost:8080 (routes settle within ~30 s)"

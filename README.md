# MITPass: College Event Pass System

Students register for campus events, get a cryptographically signed QR pass, volunteers scan it at the gate, and attendance shows up live. Built as Spring Boot microservices for the Web Programming (Java) course.

- **Plan and architecture:** [PROJECT.md](PROJECT.md)
- **Current status and work log:** [log.md](log.md)
- **Decisions (versions, toolchain, API conventions):** [docs/DECISIONS.md](docs/DECISIONS.md)
- **Phase plans:** [docs/plans/](docs/plans/) (Phase 1: [phase-1.md](docs/plans/phase-1.md))
- **UI reference:** [MITPass App Screens.html](MITPass%20App%20Screens.html) (open in a browser)

## Status

**Phase 1 (foundation and event catalogue) is done.** Eureka, the API gateway and `event-service` (events, sessions, venues, categories) run against PostgreSQL, with Swagger UI at the gateway. Authentication arrives in Phase 2; until then every endpoint is open.

## Quick start

All commands run from the repo root. Each block shows Git Bash first and PowerShell second where they differ.

### 1. One-time setup

| Need | How |
|---|---|
| **JDK 21** | Project-local, nothing global: `powershell -ExecutionPolicy Bypass -File scripts\setup-jdk.ps1` unpacks Temurin 21 into `.tools/jdk-21/` |
| **Maven** | Nothing to install. The wrapper (`./mvnw`, `mvnw.cmd`) downloads Maven 3.9.16 on first use |
| **Docker Desktop** | Install it and **start it**. It must be running for steps 2 and 5 |

When `JAVA_HOME` is unset, the wrapper finds `.tools/jdk-21` by itself. For other commands, point a shell at that JDK with `source scripts/env.sh` (Git Bash) or `. .\scripts\env.ps1` (PowerShell). VS Code terminals already have it.

### 2. Start the infrastructure

```bash
docker compose -f infra/docker-compose.yml up -d --wait
```

This starts PostgreSQL 16 (one database per service), RabbitMQ and Mailpit, and waits until all three are healthy.

### 3. Build

```bash
./mvnw install -DskipTests          # PowerShell: .\mvnw.cmd install -DskipTests
```

`install` puts `common-web` in your local Maven repository, which `-pl` in step 4 needs. Drop `-DskipTests` to run the full test suite (it needs Docker).

### 4. Run the services: three terminals, in this order

```bash
./mvnw -pl platform/discovery-server spring-boot:run     # 1. Eureka         :8761
./mvnw -pl services/event-service spring-boot:run        # 2. event-service  :8082
./mvnw -pl platform/api-gateway spring-boot:run          # 3. gateway        :8080
```

On first start, `event-service` creates its schema with Flyway and loads six demo events. Through the gateway, the API answers `503` for up to ~30 s after start, until the gateway has fetched the service from Eureka.

### 5. Try it

| What | Where |
|---|---|
| **Swagger UI (through the gateway)** | http://localhost:8080/swagger-ui.html: pick an operation, then *Try it out* |
| Eureka dashboard | http://localhost:8761 (shows `API-GATEWAY` and `EVENT-SERVICE`) |
| Ready-made requests | [docs/http/phase-1.http](docs/http/phase-1.http): create, search, and each error case (400, 404, 409, 422) |
| RabbitMQ UI | http://localhost:15672 (`mitpass` / `mitpass`) |
| Mailpit inbox | http://localhost:8025 |

```bash
curl "http://localhost:8080/api/events?q=hack&category=TECHNICAL&from=2026-11-01&to=2026-11-30"
```

Errors come back as `application/problem+json` (RFC 9457), with an `errors[]` array for validation failures and a `correlationId` that also appears in the service's log lines.

### 6. Stop

Press Ctrl+C in each service terminal, then:

```bash
docker compose -f infra/docker-compose.yml down        # keeps the data; add -v to wipe it
```

### Tests

```bash
./mvnw verify          # formatting check (Spotless), unit, web-slice and Testcontainers tests
./mvnw spotless:apply  # fix formatting before committing
```

GitHub Actions runs `./mvnw verify` on every push and pull request ([.github/workflows/ci.yml](.github/workflows/ci.yml)).

### Troubleshooting

| Symptom | Fix |
|---|---|
| `MITPass needs JDK 21+` (enforcer) or `release version 21 not supported` | `JAVA_HOME` points at an older JDK. Unset it, or run `scripts/setup-jdk.ps1` |
| `docker: command not found` | The terminal was opened before Docker was installed. Open a new one |
| Testcontainers: `Could not find a valid Docker environment` | Start Docker Desktop |
| `503` from :8080 right after start | Wait ~30 s for Eureka registration |
| Port 5432 / 8080 already in use | Stop whatever else uses it, or change the port in `infra/docker-compose.yml` / `application.yml` |

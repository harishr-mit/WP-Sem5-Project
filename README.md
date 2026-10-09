# MITPass: College Event Pass System

Students register for campus events, get a cryptographically signed QR pass, volunteers scan it at the gate, and attendance shows up live. Built as Spring Boot microservices for the Web Programming (Java) course.

- **Plan and architecture:** [PROJECT.md](PROJECT.md)
- **Current status and work log:** [log.md](log.md)
- **Decisions (versions, toolchain):** [docs/DECISIONS.md](docs/DECISIONS.md)
- **Current phase plan:** [docs/plans/phase-1.md](docs/plans/phase-1.md)
- **UI reference:** [MITPass App Screens.html](MITPass%20App%20Screens.html) (open in a browser)

## Prerequisites

| Tool | How |
|---|---|
| JDK 21 | Project-local, nothing global: `powershell -ExecutionPolicy Bypass -File scripts\setup-jdk.ps1` (unpacks Temurin 21 into `.tools/jdk-21/`) |
| Maven | None to install. Use the wrapper (`./mvnw` / `mvnw.cmd`), which downloads Maven 3.9.16 on first use |
| Docker Desktop | Needed for `docker compose` and Testcontainers; must be running |

When `JAVA_HOME` is unset, the wrapper uses `.tools/jdk-21` automatically. To use that JDK for other commands in a shell, run `. .\scripts\env.ps1` (PowerShell) or `source scripts/env.sh` (Git Bash). VS Code terminals already have it.

## Build

```bash
./mvnw verify          # Git Bash / Linux / macOS
.\mvnw.cmd verify      # PowerShell / cmd
```

## Status

The project skeleton is in place, and Phase 1 (foundation and event catalogue) is next. Instructions for running the services arrive with Phase 1.

Shared context for every agent working in this directory. Read this file **before** starting work and update it **as** you work.

## Status Legend

|Marker|Meaning|
|-|-|
|`\[ ]` TODO|Planned, not started|
|`\[\~]` WORKING|In progress (set *before* starting the task)|
|`\[x]` DONE|Completed and verified|
|`\[!]` FAILED|Attempted but failed or interrupted; add a note on why|

## Rules for Agents

1. **Read first.** Check "Current Status" and the latest log entries to learn context and what is in flight.
2. **Plan as TODOs.** Add your tasks under "Task List" as `\[ ]` before touching any files.
3. **Mark `\[\~]` before starting** a task, and `\[x]` immediately after finishing it. Never batch updates at the end.
4. **Log every change** (file created/edited/deleted/moved) in the "Change Log" with timestamp, agent/model name, and files touched.
5. **On failure, don't delete.** Mark the task `\[!]` and record the error, last successful step, and any partial changes so the next agent can diagnose and resume.
6. **Recovering from an interrupted run:** any task still `\[\~]` when you start means the previous agent stopped midway. Inspect the files listed in its log entry (`git status` / `git diff`), then either finish it or mark it `\[!]` with notes.
7. Keep entries append-only, newest first in the Change Log. Don't rewrite history.

## Current Status

* **Last updated:** 2026-10-09 (Claude Opus 5.5) — **Project initialized (T13), Phase 1 planned (T14).** Next: T03.1 per `docs/plans/phase-1.md`.
* **Project:** MITPass — College Event Pass System. Students register for campus events, receive a cryptographically signed QR pass, volunteers scan it at the gate, and attendance is recorded live. Built as a Java / Spring microservices system to demonstrate Spring MVC, REST, JPA/Hibernate, authentication, authorization, QR generation & verification, transactional DB operations, state management and microservice architecture.
* **Plan:** `PROJECT.md` — description, architecture, tech stack, and an 8-phase roadmap (each phase has deliverables, concepts demonstrated, and exit criteria).
* **UI reference:** `MITPass App Screens.html` (repo root): mockup of the 7-screen flow. A rough guide for flow and look, not a spec; the owner updates it. How to use it: `PROJECT.md` §2 "UI reference".
* **Architecture (planned):** Web Portal (Spring MVC + Thymeleaf) → API Gateway (:8080) → auth / event / pass / checkin / notification services, Eureka discovery, PostgreSQL (database per service), RabbitMQ (domain events via transactional outbox), Redis.
* **Stack (pinned, see `docs/DECISIONS.md`):** Java 21 (project-local Temurin in `.tools/jdk-21`), Spring Boot 4.1.1, Spring Cloud 2025.1.3, Maven 3.9.16 through the wrapper, Docker Desktop 4.94.0.
* **Commands:**
  * First setup on a clean clone: `powershell -ExecutionPolicy Bypass -File scripts\setup-jdk.ps1`
  * Build: `./mvnw verify` (Git Bash) / `.\mvnw.cmd verify` (PowerShell). With `JAVA_HOME` unset, the wrapper uses `.tools/jdk-21`. The system `java` on PATH is **Java 8**, so don't call `java` / `javac` directly; first run `. .\scripts\env.ps1` or `source scripts/env.sh`.
  * Docker: Docker Desktop must be running. Shells opened before the install lack `docker` on PATH (`C:\Program Files\Docker\Docker\resources\bin`).
* **Blockers / Notes:** None. Phase 1 has three open owner questions with defaults (`docs/plans/phase-1.md` §14). `mvnw` is staged with the executable bit (`100755`); everything else is still uncommitted.

## Task List

* [x] T01: Reset `log.md` for the new project (old NoBu Shooter history removed)
* [x] T02: Write `PROJECT.md` — description, goal, architecture, tech stack, 8-phase roadmap
* [ ] T03: **Phase 1** — Foundation & event catalogue (PROJECT.md §P1; plan and "done when" checks: `docs/plans/phase-1.md` §12)
  * [ ] T03.1: Build conventions: Spotless (palantir), compiler lint, MapStruct processor config
  * [ ] T03.2: Infra: `infra/docker-compose.yml` (Postgres 16, RabbitMQ 4, Mailpit) + `infra/postgres/init.sql` (5 DBs)
  * [ ] T03.3: `platform/discovery-server` (Eureka :8761)
  * [ ] T03.4: `libs/common-web` (ProblemDetail advice, exceptions, PageResponse, correlation-ID filter, auto-config)
  * [ ] T03.5: event-service persistence: entities, auditing, Flyway V1/V2, repositories, Testcontainers repo tests
  * [ ] T03.6: event-service REST: DTOs, MapStruct, services, controllers, Specifications search, validation, tests
  * [ ] T03.7: springdoc + `platform/api-gateway` (routes, correlation filter, Swagger UI at :8080)
  * [ ] T03.8: Demo seed (`R__demo_seed.sql`) + `docs/http/phase-1.http`
  * [ ] T03.9: GitHub Actions CI (`./mvnw verify`)
  * [ ] T03.10: Exit demo (5 criteria) + docs; nothing left running
* [ ] T04: **Phase 2** — Identity, authentication & authorization (PROJECT.md §P2)
* [ ] T05: **Phase 3** — Registration & transactional core (PROJECT.md §P3)
* [ ] T06: **Phase 4** — Pass issuance & QR generation (PROJECT.md §P4)
* [ ] T07: **Phase 5** — Scan, verify & attendance (PROJECT.md §P5)
* [ ] T08: **Phase 6** — Web portal, Spring MVC (PROJECT.md §P6)
* [ ] T09: **Phase 7** — Real-time dashboard, notifications & offline scanning (PROJECT.md §P7)
* [ ] T10: **Phase 8** — Hardening, observability & showcase (PROJECT.md §P8)
* [x] T11: UI mockup of the end app (visual only, no code) — https://claude.ai/artifact/WyLQJ9nh1AcMkVossG6WaH
* [x] T12: Rename project PassPort → MITPass everywhere; point the docs at `MITPass App Screens.html` as the UI reference
* [x] T13: Project initialization: toolchain + repo skeleton
  * [x] T13.1: Docker Desktop via winget (global install, approved by the owner 2026-10-09). 4.94.0, engine 29.8.2, Compose v5.5.1; `hello-world` ran
  * [x] T13.2: Project-local JDK 21 (`scripts/setup-jdk.ps1` → `.tools/jdk-21/`, gitignored)
  * [x] T13.3: Repo hygiene: `.gitignore`, `.gitattributes`, `.editorconfig`, `.vscode/settings.json`
  * [x] T13.4: Maven wrapper (only-script, Maven 3.9.16) + root `pom.xml` (Boot 4.1.1, Cloud 2025.1.3, Java 21, enforcer)
  * [x] T13.5: Verify: `./mvnw -v` uses JDK 21; `./mvnw validate` resolves both BOMs
  * [x] T13.6: Docs: `README.md` stub, `docs/DECISIONS.md`, PROJECT.md §4 version pins, log Current Status commands
* [x] T14: Plan Phase 1 → `docs/plans/phase-1.md` + T03 sub-tasks
* [x] T15: AGENTS.md "Git & Commits": prompt to commit before/after each phase (owner permission per commit); exec-bit commands

*Add new tasks at the bottom with the next free ID. Never reuse or renumber IDs.*


## Change Log

<!-- Newest first. Copy the template below for each entry. -->

<!--
### YYYY-MM-DD HH:MM — Agent (Model) — Short title

* **Task:** Txx — what was attempted
* **Status:** DONE | WORKING | FAILED
* **Files:** created / edited / deleted / moved
* **Details:** what changed and how it was verified
* **Notes / Errors:** anything the next agent needs to know
-->

### 2026-10-09 — Claude (Opus 5.5) — T15: commit rule + pre-Phase 1 commit

* **Task:** T15. Owner asked for a commit behaviour ("prompt to commit before and after each phase under user permission") and approved the Phase 1 plan.
* **Status:** DONE
* **Files:** `AGENTS.md` (new "Git & Commits" section: per-phase commit prompts, message style, `git add --chmod=+x` / `git update-index --chmod=+x`), `log.md`. The owner had also added "Mid-run Behavior" to `AGENTS.md` (.gitignore review after installs, no tracked dependencies).
* **Details:** Asked before Phase 1; the owner approved the commit `chore: initialize project toolchain and plan Phase 1` (no push).
* **Notes / Errors:** none.

### 2026-10-09 — Claude (Opus 5.5) — Fix: mvnw executable bit

* **Task:** follow-up to T13; the owner's `git update-index --chmod=+x mvnw` failed ("cannot add to the index - missing --add option?")
* **Status:** DONE
* **Files:** git index (`mvnw` staged as `100755`); `docs/DECISIONS.md` D5, `docs/plans/phase-1.md` T03.9 row, `log.md` Current Status (instruction corrected)
* **Details:** `update-index --chmod` only works on tracked files, and `mvnw` was untracked, so the instruction in the previous entry was wrong. Ran `git add --chmod=+x mvnw` instead; `git ls-files -s mvnw` → `100755`. With `core.filemode=false`, later `git add` runs keep the mode.
* **Notes / Errors:** Only `mvnw` is staged; nothing is committed.

### 2026-10-09 — Claude (Opus 5.5) — T13/T14: Project initialization + Phase 1 plan

* **Task:** T13 (toolchain + skeleton), T14 (Phase 1 plan)
* **Status:** DONE
* **Files:**
  * created: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `scripts/setup-jdk.ps1`, `scripts/env.ps1`, `scripts/env.sh`, `.gitignore`, `.gitattributes`, `.editorconfig`, `.vscode/settings.json`, `README.md`, `docs/DECISIONS.md`, `docs/plans/phase-1.md`
  * edited: `PROJECT.md` (§4 Framework / Build / Testing rows pinned to Boot 4.1 + Cloud 2025.1, JUnit 6, Testcontainers 2; repo layout gains `mvnw`, `scripts/`, `.tools/`, `docs/plans/`), `log.md`
  * outside the repo: `.tools/jdk-21/` (gitignored), Maven 3.9.16 in `~/.m2/wrapper/dists`, Docker Desktop 4.94.0 (global, approved)
* **Details:**
  * Toolchain check found no JDK 21 (only a Java 8 JRE on PATH), no Maven and no Docker; Node 26.4 and WSL2 (Ubuntu) are present. Owner chose a project-local JDK and approved `winget install Docker.DockerDesktop`.
  * Versions came from Maven Central metadata and spring.io on 2026-10-09: Boot 4.1.1 is the newest GA (4.2 is at M2); Cloud 2025.1.3 supports Boot 4.1 and is the only supported train; springdoc 3.1.1 (3.1.0 moved to Boot 4.1); MapStruct 1.6.3. Reasons and the managed library versions are in `docs/DECISIONS.md` D1.
  * JDK: Temurin `jdk-21.0.12.1+1`, SHA-256 pinned in the script; download verified. Maven wrapper 3.3.4 `only-script` taken from Maven Central (SHA-1 checked against Central, scripts reviewed before use); Maven 3.9.16 zip SHA-256 pinned in the wrapper properties (computed after verifying Central's SHA-512).
  * `mvnw` / `mvnw.cmd` carry a block marked `MITPass:` that sets `JAVA_HOME=.tools/jdk-21` when it is unset. Reapply it after a wrapper upgrade (D2).
  * Verified: `./mvnw -v` (Git Bash) and `mvnw.cmd -v` (PowerShell) both run Maven 3.9.16 on Temurin 21 from `.tools/jdk-21` with `JAVA_HOME` unset; `validate` passes the enforcer rules and resolves both BOMs. Docker engine 29.8.2 and Compose v5.5.1 are up; `hello-world` ran, then the container and image were removed.
  * Before writing the plan, these Boot 4 facts were checked against the actual jars: `@WebMvcTest` / `@DataJpaTest` packages, `@MockBean` removed, `spring-boot-starter-web` deprecated in favour of `-webmvc`, gateway / eureka / testcontainers artifact names. Correction found: `Specification.where` is *not* deprecated in Spring Data JPA 4.1.1; the plan says so.
  * Plan: `docs/plans/phase-1.md`: exit criteria, scope, module layout, decisions, data model, REST API and error contract, gateway/infra config, tests, 10 steps (= T03.1–T03.10) with "done when" checks, Boot 4 gotchas, 3 owner questions with defaults.
* **Notes / Errors:**
  * Nothing is committed (the owner didn't ask). Before the first commit of `mvnw`, run `git update-index --chmod=+x mvnw`, or Linux CI fails.
  * Docker Desktop was stopped at the end of the run (AGENTS.md: no running instances). Start it before T03.2.
  * `.gitattributes` marks `MITPass App Screens.html` as `-diff`; revert that line if the owner wants text diffs of it.

### 2026-10-09 — Claude (Opus 5.5) — AGENTS.md: local-only dependency rule

* **Task:** add an owner rule to `AGENTS.md`
* **Status:** DONE
* **Files:** `AGENTS.md` (new "Dependencies (strict)" section), `log.md` (this entry)
* **Details:** All dependency installs stay project-local (Maven wrapper, npm without `-g` / `npx`, docker compose). A global install only when absolutely necessary, and only with the user's explicit permission; log it here when it happens.
* **Notes / Errors:** Maven's shared download cache (`~/.m2`) is normal build behaviour, not a global install.

### 2026-10-09 — Claude (Opus 5.5) — T12: Rename to MITPass + UI reference

* **Task:** T12 — rename the project and link the exported mockup from the docs
* **Status:** DONE
* **Files:** `PassPort App Screens.html` → `MITPass App Screens.html` (renamed, contents patched); `PROJECT.md`, `log.md`, `AGENTS.md` (edited). The claude.ai canvas was retitled and republished too.
* **Details:**
  - HTML export: the screens are gzip+base64 entries in a `__bundler/manifest` script. Decoded each entry, replaced `PASSPORT`→`MITPASS` (wordmarks on screens 1, 4, 7) and the `P` logo monogram → `M` (4 places), re-encoded, and renamed the 3 plain-text titles. Round-trip decode checked: 0 old-name hits left. Untouched original backed up in the session scratchpad (not the repo).
  - `PROJECT.md`: renamed; added a UI-reference callout under the title and a "UI reference" subsection at the end of §2 (screen → role → service → phase table, plus rules: guide not spec, the plan wins on behaviour, rebuild in Thymeleaf instead of copying markup). P6 and P7 point to it. Repo layout root `passport/` → `mitpass/`.
  - The project name in older log entries is renamed too, at the owner's request.
* **Notes / Errors:** When the owner re-exports from the canvas, the file name should come out as `MITPass App Screens.html` again. If it doesn't, keep that name so the doc links still work.

### 2026-10-09 — Claude (Opus 5.5) — T11: UI mockup canvas

* **Task:** T11 — show what the finished app looks like
* **Status:** DONE
* **Files:** none in the repo; `log.md` (this entry). Mockup lives in a claude.ai Design canvas: https://claude.ai/artifact/WyLQJ9nh1AcMkVossG6WaH (exported to the repo later as `MITPass App Screens.html`, see T12)
* **Details:** 7 static screens in flow order: 1 Register (sign-up), 2 Event detail, 3 Pass issued, 4 Wallet with live QR, 5 Gate scanner, 6 Verified result (+ refused-pass examples), 7 Organizer live attendance dashboard. Visual language: passport maroon cover, security-paper grey-blue ground, Big Shoulders Display / Hanken Grotesk / IBM Plex Mono. Use it as the visual reference for Phase 6 (web portal).
* **Notes / Errors:** Visual only; the owner asked for UI and flow, no functionality.

### 2026-10-09 — Claude (Opus 5.5) — Project initialized: log reset + PROJECT.md

* **Task:** T01, T02 — initialize the log for a new project and write the project plan
* **Status:** DONE
* **Files:** `log.md` (rewritten; previous NoBu Shooter tasks/history removed), `PROJECT.md` (created)
* **Details:** `PROJECT.md` describes MITPass (College Event Pass System): goal, feature set, microservice architecture, tech stack, domain state machines, concept-coverage matrix, and an 8-phase roadmap of roughly equal size. Phase tasks seeded as T03–T10.
* **Notes / Errors:** No code exists yet. Exact Spring Boot / Spring Cloud versions are deliberately pinned during Phase 1 (pick a compatible release-train pair at scaffold time).

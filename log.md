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

* **Last updated:** 2026-10-16 (Claude Opus 5.5) — **Phase 4 planned (T27)** on branch `phase4` (from `main` after PR #2; Phase 3 checked and merged by the owner). Plan: `docs/plans/phase-4.md`; owner answered its §19 (Q1 ACTIVE = live code unlocks 2 h before doors open; Q2 minimal DLQ now, **completed in P7**; Q3 new `EventRescheduled`), recorded in PROJECT.md §3/§P4/§P7 and DECISIONS D12/D13 (T06.1 done). Next: the owner commits the planning docs and says go → T06.2. Phase 3 (T05) summary: event lifecycle + scheduler, overbooking-proof seat claims, waitlist promotion, eligibility, Idempotency-Key, outbox → RabbitMQ, `web-portal` slice (:8090); 285 tests green; security matrix 253/253. Parked questions for after P8: `docs/REVIEW-LATER.md`. Since 2026-10-15 (T21) P3–P5 each also include a thin `web-portal` slice (PROJECT.md §5). The shortcuts are intended, and P6 replaces them (`docs/DECISIONS.md` D9).
* **Project:** MITPass — College Event Pass System. Students register for campus events, receive a cryptographically signed QR pass, volunteers scan it at the gate, and attendance is recorded live. Built as a Java / Spring microservices system to demonstrate Spring MVC, REST, JPA/Hibernate, authentication, authorization, QR generation & verification, transactional DB operations, state management and microservice architecture.
* **Plan:** `PROJECT.md` — description, architecture, tech stack, and an 8-phase roadmap (each phase has deliverables, concepts demonstrated, and exit criteria).
* **UI reference:** `MITPass App Screens.html` (repo root): mockup of the 7-screen flow. A rough guide for flow and look, not a spec; the owner updates it. How to use it: `PROJECT.md` §2 "UI reference".
* **Architecture (planned):** Web Portal (Spring MVC + Thymeleaf) → API Gateway (:8080) → auth / event / pass / checkin / notification services, Eureka discovery, PostgreSQL (database per service), RabbitMQ (domain events via transactional outbox), Redis.
* **Stack (pinned, see `docs/DECISIONS.md`):** Java 21 (project-local Temurin in `.tools/jdk-21`), Spring Boot 4.1.1, Spring Cloud 2025.1.3, Maven 3.9.16 through the wrapper, Docker Desktop 4.94.0.
* **Built so far:** `libs/common-web` (ProblemDetail errors, PageResponse, correlation ids), `libs/common-security` (JWT resource server via JWKS/Eureka, roles, `CurrentUser`, 401/403 bodies), `libs/security-test-support` (test tokens, `SecurityMatrix`), `platform/discovery-server` (:8761), `platform/api-gateway` (:8080; JWT pre-check, CORS, Swagger UI), `services/auth-service` (:8081; sign-up/verify/login/refresh/JWKS/users), `services/event-service` (:8082; catalogue, ownership, gates, volunteers). Conventions: `docs/DECISIONS.md` D6 (REST), D7 (security), D8 (demo accounts, password `Passw0rd!`), D9 (portal slices).
* **Commands** (full walkthrough: `README.md` "Quick start"):
  * First setup on a clean clone: `powershell -ExecutionPolicy Bypass -File scripts\setup-jdk.ps1`
  * Build + all tests: `./mvnw verify` (Git Bash) / `.\mvnw.cmd verify` (PowerShell); `./mvnw spotless:apply` fixes formatting. With `JAVA_HOME` unset, the wrapper uses `.tools/jdk-21`. The system `java` on PATH is **Java 8**, so don't call `java` directly; use `.tools/jdk-21/bin/java` or first run `. .\scripts\env.ps1` / `source scripts/env.sh`.
  * Infra: `docker compose -f infra/docker-compose.yml up -d --wait` / `down` (Docker Desktop must be running).
  * Run: `./mvnw install -DskipTests`, then `./mvnw -pl <module> spring-boot:run` for discovery-server → auth-service → event-service → api-gateway (or `.tools/jdk-21/bin/java -jar <module>/target/<artifact>.jar`). Demo requests: `docs/http/phase-2.http`.
* **Blockers / Notes:**
  * Agents: stopping a background `spring-boot:run` task leaves Maven and the forked app JVM running on Windows. Kill them by PID afterwards (check `Get-NetTCPConnection -LocalPort 8761,8080,8081,8082`). Running the jars with `java -jar` as background tasks and stopping the task ended the JVM cleanly (2026-10-11).
  * Load-balanced client beans must be `@Bean(defaultCandidate = false)` and injected with `@LoadBalanced`; otherwise Eureka's own client picks them up and the service never registers (plan phase-2 §16).
  * The event-service test suite failed once (cause not captured) while compose was also up; two later full runs were green. Watch for a flaky test.
  * Port 8080 is sometimes taken by the owner's unrelated Node server (`server/src/main.ts`). It isn't ours; don't kill it. The gateway can't start while it runs.
  * GitHub Actions CI: first run on `baa98cd` passed (pushed 2026-10-10 with the owner's approval).
  * After the Phase 2 exit demo (2026-10-11): 4 services stopped, `docker compose down` (volumes kept), Docker Desktop stopped. Nothing is running.

## Task List

* [x] T01: Reset `log.md` for the new project (old NoBu Shooter history removed)
* [x] T02: Write `PROJECT.md` — description, goal, architecture, tech stack, 8-phase roadmap
* [x] T03: **Phase 1** — Foundation & event catalogue (PROJECT.md §P1; plan and "done when" checks: `docs/plans/phase-1.md` §12, as built §14). Plan approved by the owner 2026-10-09; pre-phase commit `5c12c51`. Done 2026-10-10; CI green on `baa98cd` (checked 2026-10-11)
  * [x] T03.1: Build conventions: Spotless (palantir), compiler lint, MapStruct processor config
  * [x] T03.2: Infra: `infra/docker-compose.yml` (Postgres 16, RabbitMQ 4, Mailpit) + `infra/postgres/init.sql` (5 DBs)
  * [x] T03.3: `platform/discovery-server` (Eureka :8761)
  * [x] T03.4: `libs/common-web` (ProblemDetail advice, exceptions, PageResponse, correlation-ID filter, auto-config)
  * [x] T03.5: event-service persistence: entities, auditing, Flyway V1/V2, repositories, Testcontainers repo tests
  * [x] T03.6: event-service REST: DTOs, MapStruct, services, controllers, Specifications search, validation, tests
  * [x] T03.7: springdoc + `platform/api-gateway` (routes, correlation filter, Swagger UI at :8080)
  * [x] T03.8: Demo seed (`R__demo_seed.sql`) + `docs/http/phase-1.http`
  * [x] T03.9: GitHub Actions CI (`./mvnw verify`): workflow written; first run green on `baa98cd`
  * [x] T03.10: Exit demo (criteria 1–4 live, 5 locally) + docs (README quick start); nothing left running
* [x] T04: **Phase 2** — Identity, authentication & authorization (PROJECT.md §P2; plan and "done when" checks: `docs/plans/phase-2.md` §13). Plan approved 2026-10-11; pre-phase commit `d1f02c5` on branch `phase2` (by the owner). Done 2026-10-11
  * [x] T04.1: common-web additions: 401/403 problem types, public `Problems` factory
  * [x] T04.2: `libs/common-security` (resource-server auto-config, JWKS via Eureka, roles, CurrentUser, 401/403 ProblemDetail, token relay) + `libs/security-test-support`
  * [x] T04.3: auth-service skeleton + persistence (Flyway V1/V2, entities, repos, demo users seed)
  * [x] T04.4: Signing keys (encrypted at rest, rotation) + JWKS + access-token minting
  * [x] T04.5: Sign-up (email/regno/password policy) + email verification via Mailpit
  * [x] T04.6: Login + lockout + refresh rotation with reuse detection + logout
  * [x] T04.7: User admin endpoints (me, list, lookup, roles, unlock, sessions, key rotate) + OpenAPI bearer
  * [x] T04.8: event-service security (JWT auditor, V3 organizer columns, ownership bean, @PreAuthorize, tests updated)
  * [x] T04.9: Gates + volunteer assignments (auth-service lookup via load-balanced RestClient + token relay)
  * [x] T04.10: Gateway edge security (reactive resource server, CORS, X-User-Id, auth routes, Swagger)
  * [x] T04.11: Security matrix tests → `docs/security-matrix.md`; `scripts/forge-token.sh`
  * [x] T04.12: Exit demo + docs (phase-2.http, README, PROJECT ticks, DECISIONS D7/D8, as-built)
* [x] T05: **Phase 3** — Registration & transactional core (PROJECT.md §P3; plan and "done when" checks: `docs/plans/phase-3.md` §15). Plan approved 2026-10-15 (answers in plan §17, D10/D11); pre-phase commit by the owner. Done 2026-10-16 (uncommitted)
  * [x] T05.1: common-web: LifecycleState, IllegalTransitionException (409 + allowedStates), new problem types
  * [x] T05.2: Event state machine: transition table, V4 (history, eligibility), lifecycle service + endpoints, edits-by-state rules
  * [x] T05.3: Lifecycle scheduler (time-based transitions, Clock, off in tests)
  * [x] T05.4: libs/common-messaging: outbox publisher (JdbcClient), relay (confirms, SKIP LOCKED), topology + tap queue, contracts
  * [x] T05.5: Registrations: V5, state machine, eligibility, seat claim (conditional update + row lock), cancel + promote, endpoints
  * [x] T05.6: Idempotency-Key on POST /api/registrations (V6, replay, mismatch 422, cleanup)
  * [x] T05.7: Outbox wiring: event-service events + auth-service UserRegistered; messages visible in RabbitMQ
  * [x] T05.8: Concurrency tests: 500/100 seats, naive contrast, cancel/promote race
  * [x] T05.9: Gateway route /api/registrations/** + security matrix rows + docs/security-matrix.md
  * [x] T05.10: web-portal skeleton + auth (form login via auth-service, session tokens, expiry, sign-up page)
  * [x] T05.11: web-portal pages: catalogue, detail, register/cancel, my registrations, organizer transitions
  * [x] T05.12: Exit demo + docs (phase-3.http, README, PROJECT ticks, DECISIONS D10/D11, as-built)
* [ ] T06: **Phase 4** — Pass issuance & QR generation (PROJECT.md §P4; plan and "done when" checks: `docs/plans/phase-4.md` §17). Plan written and answered 2026-10-16 (plan §19, D12/D13); waiting for the owner's pre-phase commit before T06.2
  * [x] T06.1: Owner decisions recorded (PROJECT.md §3/§P4, DECISIONS D12/D13 drafts) before code
  * [ ] T06.2: common-security: `KeyEncryptor` moved from auth-service (format unchanged)
  * [ ] T06.3: common-messaging consumer side: envelope reader, IdempotentConsumer, processed_message DDL, dead-letter topology, PassIssued/PassRevoked/EventRescheduled
  * [ ] T06.4: event-service: V7 (static_qr_allowed, pass_color), contrast check, EventRescheduled, seed
  * [ ] T06.5: pass-service skeleton (:8083), V1, gateway routes + Swagger + public JWKS path
  * [ ] T06.6: Domain: PassStatus state machine, PassNumber (Luhn, ISIN-style letters), history, EventRef
  * [ ] T06.7: ES256 keys (encrypted, rotation, max_token_exp), pass JWKS, PassTokenService, `scripts/VerifyPassToken.java`
  * [ ] T06.8: Consumers: inbox queue + DLQ, issue/revoke/saga/expire/reschedule, tombstones, outbox
  * [ ] T06.9: Pass lifecycle scheduler (unlock, expiry)
  * [ ] T06.10: ZXing QR (PNG/SVG, level H, logo, colours), EventCatalogClient, live/static endpoints
  * [ ] T06.11: PDF ticket (OpenPDF)
  * [ ] T06.12: Read + revoke API, revocation feed, key rotation, security matrix + docs
  * [ ] T06.13: Portal slice + exit demo + docs
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
* [x] T16: Verify Phase 1 is complete; plan Phase 2 → `docs/plans/phase-2.md` + T04 sub-tasks (no code)
* [x] T17: Start MITPass services for owner preview; leave running on request
* [x] T18: Safely stop MITPass services, infrastructure, and Docker Desktop
* [x] T19: Fix CI failure on PR #1 (phase2 → main): order-dependent venue count in event-service test
* [x] T20: Archive the CI error log under `archive/` + AGENTS.md skip rule
* [x] T21: PROJECT.md: thin web-portal slice in P3–P5 so each backend phase is visible in a browser; P6 becomes the full portal; schedule the admin health overview
* [x] T22: AGENTS.md "Workflow for any change": owner's workflow adapted to MITPass
* [x] T23: AGENTS.md "Parts to keep in mind" + "Working rules", adapted to MITPass
* [x] T24: Plan Phase 3 → `docs/plans/phase-3.md` + T05 sub-tasks (no code)
* [x] T25: `docs/REVIEW-LATER.md` (parked questions: RL1 waitlist after closing, RL2 manual COMPLETED, RL3 undo LIVE after check-ins) + PROJECT.md "After P8" line
* [ ] T26: **After P8:** go through `docs/REVIEW-LATER.md` with the owner, item by item
* [x] T27: Plan Phase 4 → `docs/plans/phase-4.md` + T06 sub-tasks (no code)
* [x] T28: `docs/REVIEW-LATER.md` RL4: check the dead-letter handling end to end after P8 (owner request)

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

### 2026-10-16 — Claude (Opus 5.5) — T28: RL4 (check the DLQ after P8)

* **Task:** T28 — owner asked to add a post-P8 check of the DLQ to the review-for-later doc
* **Status:** DONE
* **Files:** `docs/REVIEW-LATER.md` (new RL4), `log.md`
* **Details:** RL4 lists what to verify after P8: DLQ per consumer, poison message lands in it, inspect/replay (idempotent), non-empty DLQ visible in health/Grafana, gaps fixed or accepted. Links PROJECT.md §P4/§P7 and D13.

### 2026-10-16 — Claude (Opus 5.5) — T06.1: Phase 4 owner decisions recorded

* **Task:** T06.1 — record the owner's answers to `docs/plans/phase-4.md` §19 before any code
* **Status:** DONE
* **Files:** `PROJECT.md` (§3 services table + `EventRescheduled`; §3 Pass row: ACTIVE = code unlock, ISSUED → EXPIRED; §P4 two new bullets: minimal DLQ marked "completed in P7", `EventRescheduled` + per-event pass settings; §P7 new bullet "complete the dead-letter handling started in P4"); `docs/DECISIONS.md` (new D12 passes/tokens/keys, D13 consumers + minimal DLQ); `docs/plans/phase-4.md` (status line, §2 heading and DLQ row, §19 resolved); `log.md`
* **Details:** Answers: Q1 unlock before start (recommended), Q2 minimal DLQ now, "clearly mark that it is to be fully completed at P7" (done in PROJECT.md §P4/§P7, D13 title and body, plan §2/§19), Q3 `EventRescheduled` (recommended). Pre-phase commit: the owner will commit the docs and then tell the agent to start; no commit made by the agent.
* **Notes / Errors:** D1's "Pinned in the root POM" line gets ZXing 3.5.4 / OpenPDF 3.0.5 when T06.5 actually pins them. Nothing running.

### 2026-10-16 — Claude (Opus 5.5) — T27: Phase 4 planned

* **Task:** T27 — owner asked to plan and prepare Phase 4 (no code)
* **Status:** DONE (plan awaits the owner's answers to §19)
* **Files:** created `docs/plans/phase-4.md`; edited `log.md` (status, T06.1–T06.13, T27, this entry), `PROJECT.md` (§P4 goal line links the plan)
* **Details:**
  * Read: PROJECT.md, DECISIONS D1–D11, phase-3 plan + as-built, REVIEW-LATER, common-messaging (publisher, relay, contracts), auth-service key code (`SigningKeyService`, `KeyEncryptor`), gateway config, infra (`pass_db` already exists, so no `down -v`), event-service DTOs (venue has `mapUrl`; event codes `^[A-Z0-9]{3,16}$`).
  * Maven Central checked 2026-10-16: ZXing 3.5.4 and OpenPDF 3.0.5 are the latest; neither is in the Boot BOM, so both get pinned in the root POM (D1). Plan uses ZXing `core` only (javase at test scope).
  * UI mockup decoded into the session scratchpad (not the repo). Screen 3 "Pass issued" (pass number `TF26-000042-2`, timeline) and screen 4 "My passes": **"Code unlocks … The scannable code appears 2 hours before doors open"**. That detail isn't in PROJECT.md; the plan uses it as the meaning of ISSUED → ACTIVE (owner question Q1).
  * Plan: pass-service :8083 with ES256 keys (encrypted like auth's; retired keys published until their longest token expires), JWS claims `iss, pid, eid, sub, iat, exp, jti, knd`, live token 45 s / static until end + 2 h, per-event `staticQrAllowed` + `passColor` in event-service (V7), idempotent consumer (`processed_message` + `UNIQUE registration_id`), reordering tombstones, saga revoke on CANCELLED, cursor revocation feed, scheduler for unlock/expiry, ZXing PNG/SVG with a drawn logo, OpenPDF A6 ticket, JDK-only `scripts/VerifyPassToken.java`, portal pages for passes and organizer revoke. 12 exit criteria, 13 steps.
* **Notes / Errors:** Defaults the owner may change (plan §2/§19): Q1 ACTIVE = code unlock + new ISSUED → EXPIRED edge; Q2 minimal DLQ pulled forward from P7; Q3 new `EventRescheduled` contract. PROJECT.md §3/§P4 are left as they are until the owner answers (T06.1). Nothing started or left running.

### 2026-10-16 — Claude (Opus 5.5) — T05.10–T05.12: web portal, exit demo, docs; Phase 3 done

* **Task:** T05.10 (portal skeleton + auth), T05.11 (portal pages), T05.12 (exit demo + docs)
* **Status:** DONE (Phase 3 complete; not committed: the post-phase commit is the owner's call)
* **Files:**
  * root `pom.xml` (module `web-portal`; `maven-jar-plugin` `forceCreation=true`)
  * new `web-portal/**`: `pom.xml`, `application.yml`, `PortalApplication`, `config/PortalProperties`, `config/SecurityConfig` (form login, CSRF, role rules, logout revokes the refresh token), `auth/PortalUser`, `auth/GatewayAuthenticationProvider`, `auth/SessionExpiryFilter`, `client/GatewayClient`, `client/ApiModels`, `client/ApiProblemException`, `client/SessionExpiredException`, `client/CorrelationIdFilter`, `web/AccountController`, `web/SignupForm`, `web/EventPagesController`, `web/MyRegistrationsController`, `web/OrganizerController`, `web/PortalAdvice`, `web/DisplayFormat`, templates (`fragments`, `login`, `signup`, `events/list`, `events/detail`, `my/registrations`, `organizer/events`), `static/css/portal.css`; tests `GatewayClientTest` (7), `PortalPagesTest` (25), `TestTokens`, `application-test.yml`
  * `libs/common-messaging`: `OutboxRelay` (connects at startup so the topology is declared), new `TopologyOnStartupTest`
  * docs: new `docs/http/phase-3.http`; `README.md` (status, five services, portal, tap queue, concurrency demo, troubleshooting); `PROJECT.md` (§P3 ticked + status); `docs/plans/phase-3.md` (new §18 As built); `docs/DECISIONS.md` (D9: two new slice rows; D11: relay startup); `log.md`
* **Details:**
  * Final `./mvnw verify` green: **285 tests** (common-web 27, common-security 9, security-test-support 15, common-messaging 11, discovery 1, gateway 18, auth-service 51, event-service 121, web-portal 32). Security matrix 253/253 (auth 85, event 168).
  * Live exit demo (compose infra, jars on the project JDK, Phase 2 volume kept, so V4–V6 and the new seed ran on existing data): 56 scripted checks (script in the session scratchpad; same steps in `docs/http/phase-3.http`). **54 passed on the first run.** The 2 failures (exchange and tap queue not in RabbitMQ before the first event) were real; see the two bugs below. After the fixes, both exist right after a restart (checked by deleting them on the broker, rebuilding, restarting). Everything else passed: Eureka lists the 4 apps; lifecycle + illegal 409 + history; promotion with the 3 messages in order on the tap queue with the request's correlation id; idempotency replay/mismatch/409; eligibility 422 with reasons; optimistic 409 + capacity rules + capacity-raise promotion; portal sign-up → Mailpit → verify → login, event page, promotion note, no token in pages, CSRF 403, HttpOnly cookie, organizer buttons without LIVE, close from the portal; scheduler closed an event by itself (history SCHEDULER/system), registering afterwards 409.
  * **Bug 1 (fixed, with a test):** Spring AMQP declares exchanges and queues only when the first connection opens, which came with the first event. The relay now opens a connection at startup (`TopologyOnStartupTest`, checked through the management API).
  * **Bug 2 (fixed, build):** after a change in `libs/*` only, `install` kept the services' old fat jars (maven-jar-plugin thought the jar up to date and `spring-boot:repackage` skipped), so the running service still had the old library. `forceCreation=true` in the root POM; verified the rebuilt jar contains the fix.
  * Portal: Jackson 3 enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default, so the portal's response models use boxed numbers/flags (a missing field must not break a page).
  * `.gitignore` reviewed: only sources are untracked, every `target/` ignored; no change needed.
* **Notes / Errors:** At the end: all 5 JVMs stopped (no java.exe with `target` in its command line, ports 8080/8081/8082/8090/8761 free), `docker compose down` (volumes kept), Docker Desktop stopped. Demo data in the kept volume now includes the demo's registrations (MINIQUIZ26: Arun CONFIRMED, Priya cancelled) and a few `D…`/`S…` events; run `docker compose down -v` for a fresh seed before a presentation.

### 2026-10-16 — Claude (Opus 5.5) — T05.6–T05.9: idempotency, outbox wiring, concurrency, gateway + matrix

* **Task:** T05.6 (Idempotency-Key), T05.7 (outbox wiring + `UserRegistered`), T05.8 (concurrency tests), T05.9 (gateway route, security matrix)
* **Status:** WORKING (T05.6–T05.9 DONE; next T05.10 web-portal)
* **Files:**
  * T05.6 event-service: `V6__idempotency_and_outbox.sql` (idempotency_record + outbox_message); new `idempotency/IdempotencyStore`, `IdempotencyProperties`; `RegistrationService.register(student, eventId, key)` + `RegisterOutcome`; `RegistrationController` (`Idempotency-Key` header with pattern check, `Idempotent-Replayed: true`); `SchedulingConfig` (binds the properties); `application.yml` (`mitpass.idempotency`); test `IdempotencyTests` (7, incl. 4 concurrent retries → one registration)
  * T05.7: event-service `pom.xml` (+common-messaging, test testcontainers-rabbitmq), `RegistrationEvents` (outbox), `EventLifecycleService` (`EventStateChanged`), `application.yml` (`spring.rabbitmq`, `mitpass.messaging`, tap queue on), test yml (relay/tap off, rabbit health off); test `MessagingFlowTest` (own context + RabbitMQ container); auth-service `pom.xml` (+common-messaging), `V3__outbox.sql`, `SignupService` (`UserRegistered`, `saveAndFlush`), `application.yml` (`spring.rabbitmq`), test yml (relay off); test `SignupOutboxTest`
  * T05.8: test `SeatClaimConcurrencyTest` (500/100, naive contrast in test code, cancel/register race); test yml Hikari pool 20
  * T05.9: gateway `application.yml` (`/api/registrations/**` on the event-service route), `EdgeSecurityTest` (+1); event-service `SecurityMatrixTest` (+8 endpoints, fixture registration owned by the matrix student); `docs/security-matrix.md` regenerated
* **Details:** Full `./mvnw verify` green: 252 tests (common-web 27, common-security 9, security-test-support 15, common-messaging 10, discovery 1, gateway 18, auth-service 51, event-service 121). Security matrix 253/253 (auth 85, event 168). Concurrency test green 3 runs in a row: real service always 100 CONFIRMED / 400 WAITLISTED / seats_taken 100 with waitlist positions 1..400; naive version confirmed 118, 117, 116; race 50/50/50. No deadlocks seen (the registration insert takes a KEY SHARE lock on the event row via the FK; Hibernate's PESSIMISTIC_WRITE on PostgreSQL doesn't conflict with it). Messages arrive in order Cancelled → WaitlistPromoted → Confirmed(viaPromotion) with the request's correlation id.
* **Notes / Errors:**
  * RabbitMQ 4 refuses transient non-exclusive queues (`transient_nonexcl_queues` deprecated, connection closed with 541): declare durable or exclusive queues.
  * **Python on Windows writes cp1252 and CRLF by default.** It mangled a `§` and turned three files to CRLF (fixed). Always pass `encoding="utf-8", newline=""` when editing files with Python here.
  * The POST /api/registrations matrix row shows ✅ 409 for students: the fixture event is a DRAFT, so the call passes security and is refused by the business rule, which is what the matrix checks.

### 2026-10-16 — Claude (Opus 5.5) — T05.4–T05.5: common-messaging, registrations

* **Task:** T05.4 (outbox library), T05.5 (registrations, seats, waitlist, eligibility)
* **Status:** WORKING (T05.4 and T05.5 DONE)
* **Files:**
  * T05.4: root `pom.xml` (module `libs/common-messaging`); new `libs/common-messaging/**`: `MessagingProperties`, `OutboxPublisher` (JdbcClient, MANDATORY), `OutboxRelay` (SmartLifecycle, SKIP LOCKED, simple confirms, retry, cleanup), `MessagingAutoConfiguration` + imports, contracts (`EventType`, `RegistrationConfirmed`, `RegistrationCancelled`, `WaitlistPromoted`, `EventStateChanged`, `UserRegistered`), reference DDL `mitpass/messaging/outbox-postgresql.sql`; tests `OutboxPublisherTest` (6), `OutboxRelayTest` (4, PostgreSQL + RabbitMQ containers)
  * T05.5 event-service: `V5__registrations.sql`; domain `RegistrationStatus`, `Registration`, `RegistrationChange`; `Event` (read-only `seatsTaken`, `getSeatsLeft`, `registrationEndsAt`); `EventRepository` (`claimSeat`, `claimSeatForPromotion`, `releaseSeat`, `findSeatsTakenById`); `RegistrationRepository`, `RegistrationChangeRepository`; services `Eligibility`, `EligibilityPolicy`, `RegistrationProblems`, `RegistrationEvents` (outbox hooks, filled in T05.7), `WaitlistService`, `RegistrationService`; `EventService` (capacity >= seats taken; a capacity increase promotes); `security/RegistrationSecurity`; web `RegistrationController`, `EventRegistrationsController`, DTOs `RegistrationResponse`, `RegistrationRequests`; detail/summary DTOs gain `seatsTaken`, `seatsLeft`; tests `RegistrationStatusTest`, `EligibilityPolicyTest`, `RegistrationServiceTest` (14), `RegistrationApiTests` (8), `TestData` helpers, `EventServiceTest`/`EventControllerTest` updated
* **Details:** common-messaging 10/10 and event-service 109/109 green. **Bug found by `EligibilityPolicyTest` and fixed:** `List.copyOf(..).contains(null)` throws, so a token without a `dept` claim would have caused a 500 on an event with department limits. Outbox order: rows of one transaction can share `occurred_at`, so the DDL has an identity `seq` and the relay orders by it. A class named `*IT` would never run (no failsafe plugin), so the relay test is `OutboxRelayTest`.
* **Notes / Errors:** Plan deviation: `waitlistCount` is on `GET /api/events/{id}/eligibility` (and countable via `GET /api/events/{id}/registrations?status=WAITLISTED`) instead of the event detail, which a MapStruct mapping can't fill without a query. The gateway route for `/api/registrations/**` comes in T05.9.

### 2026-10-16 — Claude (Opus 5.5) — T05.1–T05.3 + T25: lifecycle core, review-for-later doc

* **Task:** owner approved the Phase 3 plan (answers in plan §17) and asked for a review-for-later doc; T05.1–T05.3 built
* **Status:** WORKING (T05 in progress; T05.1–T05.3 DONE)
* **Files:**
  * docs: created `docs/REVIEW-LATER.md` (RL1 waitlist after closing, RL2 manual COMPLETED, RL3 undo LIVE after check-ins); `PROJECT.md` (§3 diagram + table: LIVE scheduler-only, LIVE → REGISTRATION_CLOSED undo, one active registration; §P3 bullets; new "After P8 — Review for later" section); `docs/DECISIONS.md` (D10 state machines/seat claims, D11 outbox); `docs/plans/phase-3.md` (§2 owner decisions, §5 table, §10 RL1 pointer, §17 resolved); `AGENTS.md` (Docs row lists REVIEW-LATER); `log.md` (T25, T26)
  * T05.1 `libs/common-web`: `ProblemException` gains extension `properties`; new `IllegalTransitionException`, `state/LifecycleState`, `ProblemTypes.ILLEGAL_TRANSITION`; tests `LifecycleStateTest`, 2 new handler cases (27 tests green)
  * T05.2 `services/event-service`: `V4__lifecycle_and_eligibility.sql`; `EventStatus` (table, manualNext, scheduledNext, isEditable, isCodeFrozen), `TransitionTrigger`, `EventStateChange` + repository; `Event` (minYear/maxYear as SMALLINT, eligibleDepartments, `moveTo`); DTOs (create/update + eligibility, detail + eligibility/allowedTransitions, `TransitionRequest`, `StateChangeResponse`), `EventCodes` department pattern; `EventMapper`; `CatalogRules` (year range, editable, code frozen); `EventLifecycleService`; `EventService` (history row on create, edit rules); `SessionService` (read-only when LIVE+); `LifecycleController`; `EventRepository` (graph + due-move queries); seed (MINIQUIZ26, AIWS26 departments, SPACETALK26 years); tests `EventStatusTest`, `LifecycleTests`, `EventServiceTest`/`EventControllerTest` updated
  * T05.3: `EventLifecycleScheduler`, `config/LifecycleSchedulerProperties`, `config/SchedulingConfig`; `application.yml` (`mitpass.events.scheduler`), test yml (scheduler off); `EventLifecycleSchedulerTest`
* **Details:** event-service 76 tests green (`./mvnw -pl services/event-service verify`). Found while building: Hibernate validate rejects `Integer` on a SMALLINT column (fixed with `@JdbcTypeCode(SqlTypes.SMALLINT)`); MapStruct update methods clear target collections through the getter, so eligibleDepartments is set by the service; one scheduler run can chain moves (OPEN → CLOSED → LIVE at the start time when no closing time is set) — intended catch-up behaviour, test adjusted.
* **Notes / Errors:** Heredocs containing apostrophes break the Bash tool wrapper here; files are written with the Write tool. A test run once failed with Eclipse "Unresolved compilation problems" classes in `target/` (the VS Code Java server compiles into the same folder); rerunning fixed it. If it recurs, run with `clean`. Docker Desktop is running (started for Testcontainers).

### 2026-10-15 — Claude (Opus 5.5) — T24: Phase 3 planned

* **Task:** T24 — owner asked to plan the implementation of Phase 3 (no code)
* **Status:** DONE (plan awaits owner approval)
* **Files:** created `docs/plans/phase-3.md`; edited `log.md` (status, T05.1–T05.12, T24, this entry), `PROJECT.md` (§P3 goal line links the plan)
* **Details:**
  * Read: PROJECT.md, DECISIONS D1–D9, phase-2 plan + as-built, event-service domain/service/migrations/seed, gateway config, auth seed and sign-up DTO. Checked in `~/.m2`: the Boot 4.1.1 BOM has `spring-boot-starter-amqp`, `-thymeleaf`, `-restclient`, `-security` (+ `-test` variants) and `thymeleaf-extras-springsecurity6`; the Testcontainers 2.0.5 BOM has `testcontainers-rabbitmq`. common-web already maps optimistic-lock failures to 409.
  * UI mockup decoded into the session scratchpad (not the repo). Screen 2 (event detail) shows the eligibility line, seats left, close time, "You're eligible", Register. One deviation recorded in plan §10: the mockup's "after closing you can join the waitlist" note doesn't match this plan's waitlist rule.
  * Plan: enum transition tables via a shared `LifecycleState` (409 with `allowedStates`), history tables, a scheduler with per-event transactions; seat claim = conditional UPDATE (fast path) + `PESSIMISTIC_WRITE` re-check (slow path); cancel locks the event row and promotes in the same transaction; read-only `seats_taken` mapping; `Idempotency-Key` stored atomically with the registration (`ON CONFLICT DO NOTHING`); new `libs/common-messaging` (JdbcClient outbox, SmartLifecycle relay with confirms + SKIP LOCKED, `mitpass.events` topic exchange, dev tap queue); `web-portal` D9 slice with form login against auth-service and tokens in the session. 10 exit criteria, 12 steps.
* **Notes / Errors:** Defaults the owner may change (plan §2/§17): one *active* registration per student per event (partial unique index, deviates from PROJECT.md's literal `unique (event_id, user_id)`), the scheduler also moves LIVE/COMPLETED, `UserRegistered` included in P3. PROJECT.md §P3 bullets left as they are until the owner answers. Nothing started or left running.

### 2026-10-15 — Claude (Opus 5.5) — T23: parts table + working rules in AGENTS.md

* **Task:** T23 — add the owner's "Parts to keep in mind" and "Working rules", adapted from another project
* **Status:** DONE
* **Files:** `AGENTS.md` (two sections after "Workflow for any change"), `log.md`
* **Details:**
  * The parts table now lists MITPass parts at their real paths (checked on disk): build/POM, libs, infra, gateway/discovery, Flyway migrations, REST APIs, auth, demo seeds, tests, docs. It also has rows for parts later phases build, marked (P3+)/(P4+): domain events, state machines, QR signing, web-portal.
  * Adapted rules: "models stay loadable forever" → published contracts only grow (migrations, REST, events, JWT claims, QR payloads). `configs/*.yaml` → `mitpass.*` in `application.yml` with `@ConfigurationProperties`. Physics sanity → `SecurityMatrixTest` / concurrency test. "No pygame in sim" → database-per-service boundary.
  * Dropped as not applicable: queue, backup, sandbox glossary, demo FPS, `assets/LICENSES.md`.
* **Notes / Errors:** The `init.sql` note (runs only on an empty volume) comes from the file's own header comment.

### 2026-10-15 — Claude (Opus 5.5) — T22: agent workflow in AGENTS.md

* **Task:** T22 — add the owner's 9-step "Workflow for any change" to AGENTS.md, adapted from another project
* **Status:** DONE
* **Files:** `AGENTS.md` (new section before "Dependencies"), `log.md`
* **Details:** Adapted for MITPass: PROJECT_CONTEXT → `PROJECT.md` plus `docs/DECISIONS.md` (in place of a §16 decisions log) and `docs/plans/phase-N.md`. Python/pytest checks → `./mvnw -pl <module> -am verify`, then `./mvnw verify`, plus a `SecurityMatrixTest` row for new endpoints. "Logic in `racer/`" → business rules in the service domain/service layer and shared code in `libs/`. Doc list → README, PROJECT.md, phase plan as-built, `.http` files, security matrix, DECISIONS. Removed: the training-run steps and the clean-tree rule (D12), which don't apply here.
* **Notes / Errors:** The original says "commit with a Conventional Commits message". Step 8 points to the existing *Git & Commits* rules instead (owner permission per commit; `Phase N:` / `chore:` subjects), so the two sections can't disagree.

### 2026-10-15 — Claude (Opus 5.5) — T21: portal slices in P3–P5

* **Task:** T21 — owner asked for a thin UI slice in each phase up to P6, just enough to see each backend phase's effects in a browser
* **Status:** DONE
* **Files:** `PROJECT.md` (§2 UI reference table, §5 intro, P3/P4/P5 "Portal slice" items and exit criteria, P6 goal and admin item), `log.md`
* **Details:**
  * §5 defines a "portal slice": plain Thymeleaf, minimal styling, no HTMX/camera/dark mode, tokens still kept server-side (BFF rule).
  * P3 creates `web-portal` (:8090): sign-up/login (re-login on expiry; refresh stays in P6), catalogue + detail, register/cancel with waitlist position, organizer lifecycle buttons. P4: "My Passes", live QR reloading every 30 s, PDF link, organizer revoke. P5: volunteer manual-entry scan + result page, organizer attendance counts + CSV link.
  * UI reference table now has "Thin page in" and "Finished in" columns. P6 goal reworded to polish the slices.
  * P6 Admin item now includes the system health overview that §2 listed but no phase scheduled.
* **Follow-up (same day):** the owner wants later sessions to understand why the slices are rough and to finish them properly in P6. Added `docs/DECISIONS.md` **D9**: the reason, the four rules every slice keeps (BFF tokens, CSRF, PRG, calls via gateway), and a table of each intended shortcut with its P6 replacement. PROJECT.md §5 and each slice item point to D9. P6 gained "replace every D9 shortcut" as a done condition.
* **Notes / Errors:** Docs only, no code. Left unchanged on purpose: `docs/plans/phase-2.md` (a finished plan) and code comments in `platform/api-gateway` and `services/auth-service` that say "P6 portal". They are still true because P6 finishes the portal. The P3 plan (`docs/plans/phase-3.md`) must include the P3 slice.

### 2026-10-11 — Claude (Opus 5.5) — T19/T20: CI fix + error-log archive rule

* **Task:** T19 (CI red on PR #1, phase2 → main), T20 (archive the CI log)
* **Status:** DONE
* **Files:** `services/event-service/src/test/java/com/mitpass/event/EventServiceApplicationTests.java`, `AGENTS.md` (archive rule), `.gitignore` (`!archive/*.log`), `archive/2026-10-11_ci-pr1_event-service-venue-count.log` (created), `log.md`
* **Details:**
  * CI failure: `EventServiceApplicationTests.referenceDataEndpoints:177` expected `$.totalElements` 5 but got 128. All event-service test classes share one Testcontainers Postgres, and `SecurityMatrixTest` commits 123 "Matrix Fixture Venue N" rows. On the runner it ran first; locally it ran later, so local `verify` passed. Fix: assert `totalElements >= 5` and that "Main Auditorium" (seeded by V2) is listed.
  * Verified: `./mvnw -pl services/event-service -am clean verify -Dsurefire.runOrder=reversealphabetical` (forces the CI order) → 47/47 pass, BUILD SUCCESS. Full `./mvnw verify` also passed earlier.
  * A first run without `clean` failed with `ClassNotFoundException: VenueRepository`: a stale `target/`, not a code problem. `clean` fixed it.
  * AGENTS.md: agents skip `archive/` unless a past failure is relevant.
* **Notes / Errors:** The archived log is the GitHub Actions job log `0_build.txt` from the owner's download `logs_103259064280/`. The rest of that folder (`build/system.txt`, 7 lines of runner metadata) was deleted with it. `.gitignore` ignores `*.log`, so an exception keeps `archive/*.log` tracked. Archive naming: `YYYY-MM-DD_<source>_<short-cause>.log`. Docker Desktop stopped at the end.
* **Git:** owner approved "commit and finish phase 2": this fix is committed on `phase2`, then `phase2` is merged into `main` (`--no-ff`, local). Owner then approved the push: `phase2` (b8d6446) and `main` (merge 98d7462) pushed; GitHub Actions `ci` green on both (runs 38145876689, 38145797409).

### 2026-10-11 — GitHub Copilot — T18: Shut down MITPass

* **Task:** T18 — stop MITPass JVMs, Compose containers, and Docker Desktop
* **Status:** DONE
* **Files:** `log.md`
* **Details:** Stopped the four confirmed service PIDs, ran `docker compose -f infra/docker-compose.yml down` (containers and network removed; volumes retained), then `docker desktop stop`. Verified no listeners remain on the app or infrastructure ports and no Docker Desktop processes remain.
* **Notes / Errors:** Windows rejected non-forced termination for the detached Java processes; no app shutdown endpoint or window was available, so used `/F` only for the four verified idle MITPass PIDs. `docker compose down` used no `-v`, preserving database data.

### 2026-10-11 — GitHub Copilot — T17: Start MITPass for owner preview

* **Task:** T17 — start the local infrastructure and Phase 1–2 services
* **Status:** DONE
* **Files:** `log.md`
* **Details:** Started Docker Compose infrastructure (PostgreSQL, RabbitMQ, Mailpit; all healthy) and the four packaged Java 21 services: discovery-server (PID 86772), auth-service (105928), event-service (62196), and api-gateway (41604). Verified all four listeners, Swagger UI HTTP 200, event catalogue HTTP 200 through both gateway and direct service, and Eureka registry entries UP. Opened Swagger UI in the integrated browser.
* **Notes / Errors:** Docker CLI was absent from this terminal's PATH, so used the installed executable under `C:\Program Files\Docker\Docker\resources\bin`. The first gateway catalogue request returned 503 while Eureka discovery warmed up; subsequent request returned 200. Services and containers intentionally remain running per owner request. Logs are under `%TEMP%\mitpass-*.log`.

### 2026-10-11 — Claude (Opus 5.5) — T04: Phase 2 implemented

* **Task:** T04.1–T04.12 per `docs/plans/phase-2.md` (approved; owner made pre-phase commit `d1f02c5` on branch `phase2`)
* **Status:** DONE (not committed: the post-phase commit is the owner's call)
* **Files:**
  * plan/docs: `docs/plans/phase-2.md` (§4/§5/§15 owner answers: departments `CSE, IT, ECE, EIE, MECH, AERO, AUTO, PROD, RPT, ROBO, AIDS`, register number never parsed; new §16 "As built"), `docs/DECISIONS.md` (D6 note, new D7 security model, D8 demo accounts), `docs/security-matrix.md` (new, generated), `docs/http/phase-2.http` (new), `docs/http/phase-1.http` (login + bearer on writes; delete targets id 2), `README.md` (status, 4 services, demo accounts, troubleshooting), `PROJECT.md` (§P2 ticked + status), `log.md`
  * root `pom.xml` (modules: common-security, security-test-support, auth-service)
  * T04.1 `libs/common-web`: `ProblemTypes` (+UNAUTHORIZED, FORBIDDEN), new `Problems`, new `ProblemException` (+ handler), `ProblemsTest`, probe case
  * T04.2 new `libs/common-security/**` (auto-config also in the `@WebMvcTest` imports), new `libs/security-test-support/**` (`TestJwtIssuer`, `TestSecurityConfig`, `TestReactiveSecurityConfig`, `SecurityMatrix`, filter-chain test)
  * T04.3–T04.7 new `services/auth-service/**` (Flyway V1/V2 + `R__demo_users.sql`, domain, repositories, keys, services, mailer, validation, controllers, tests incl. `SecurityMatrixTest`)
  * T04.8–T04.9 `services/event-service`: `pom.xml`, `application.yml`, test `application-test.yml`, V3 migration, `R__demo_seed.sql` (owners, gates), `Event` (+organizer), mapper/DTOs/criteria/specifications/repository, `EventService.create(organizer, request)`, `JpaConfig` (JWT auditor), `OpenApiConfig` (bearer), controllers (`@PreAuthorize`), new `security/EventSecurity`, `client/AuthServiceClient`, `config/ClientConfig`, `config/TimeConfig`, `EventGate`, `VolunteerAssignment`, `StaffingService`, `StaffingController`, `StaffingDtos`; tests updated (tokens) + new `EventIntegrationTest`, `OwnershipAndStaffingTests`, `AuthServiceClientTest`, `SecurityMatrixTest`
  * T04.10 `platform/api-gateway`: `pom.xml`, `application.yml` (auth routes, public paths, CORS, Swagger), `CorrelationIdGlobalFilter` renamed to `CorrelationIdWebFilter` (git mv, test too), new `security/EdgeSecurityConfig`, `CorsProperties`, `ProblemServerAuthenticationEntryPoint`, `UserHeadersGlobalFilter`, tests `EdgeSecurityTest`, `UserHeadersGlobalFilterTest`
  * T04.11 `scripts/forge-token.sh` (staged `100755` with `git add --chmod=+x`)
* **Details:**
  * Tests: 158, all green in two full `./mvnw verify` runs (common-web 20, common-security 9, security-test-support 15, discovery 1, gateway 17, auth-service 49, event-service 47). 0 compiler warnings. Security matrix 85/85 (auth) + 120/120 (event).
  * Live exit demo on compose infra (Phase 1 volume kept, so V3 back-filled existing events): 36/36 scripted checks covering exit criteria 1–3 and 5–9 (script in the session scratchpad; the same steps are in `docs/http/phase-2.http`). Criteria 4 and 10: the matrix tests and `verify` above; CI runs on the next push.
  * Found while building (all in plan §16): `Encryptors.*` deprecated in Security 7.1, so `AesGcmBytesEncryptor`; `CHAR(64)` breaks `ddl-auto=validate`; Spring Security's WebFilter runs before gateway GlobalFilters (correlation filter became a WebFilter); a test-bean name clash in the gateway; a load-balanced `RestClient.Builder` bean hijacked the Eureka client (found only live; fixed with `defaultCandidate = false` in three places).
  * `.gitignore` reviewed: only sources untracked, every `target/` ignored; no change needed.
* **Notes / Errors:**
  * One event-service test run failed without a captured cause (compose was up at the same time); not reproduced in two full runs.
  * Docker Desktop was started for Testcontainers and the demo, then stopped. All 4 service JVMs stopped (checked: no java.exe with `target` in its command line), `docker compose down` (volumes kept).

### 2026-10-11 — Claude (Opus 5.5) — T16: Phase 1 verified, Phase 2 planned

* **Task:** T16. The owner asked to check Phase 1 and, if it was complete, plan Phase 2 (no code).
* **Status:** DONE
* **Files:**
  * created: `docs/plans/phase-2.md`
  * edited: `log.md` (status, T03 CI notes, T04.1–T04.12, T16, this entry), `PROJECT.md` (§P1 CI bullet; §P2 goal reworded for public catalogue reads, sign-up domains, stateless revocation, plan link), `docs/plans/phase-1.md` (§14 CI now verified)
  * outside the repo: security jars (`spring-security-oauth2-jose` / `-resource-server` 7.1.1, two starter POMs) fetched into `~/.m2` to check APIs; UI bundle decoded into the session scratchpad (not the repo)
* **Details:**
  * Phase 1 check: every T03 sub-task done; GitHub Actions (public API) shows `ci` green on `baa98cd` and `6b2699d` (HEAD). Since HEAD the working tree only changes `.vscode/settings.json` and `AGENTS.md` (owner edits, no code), so the local `verify` wasn't rerun and Docker Desktop stayed off.
  * Owner decisions (2026-10-11): sign-up from `<regno>@student.annauniv.edu` **and** Gmail, both as students; register number = admission year + programme code (501–512) + roll no, e.g. `2026509032`, sometimes 2 digits longer (pattern `^\d{10}(\d{2})?$`); catalogue GETs public; stateless access-token revocation (no Redis in P2). A web search didn't find an official register-number breakdown, so the programme-code → department map stays an open question (plan §15).
  * Verified for the plan: Boot 4.1.1 BOM has `spring-boot-starter-security-oauth2-resource-server` (the `spring-boot-starter-oauth2-resource-server` POM says deprecated), `-security-test`, `-mail`; Spring Security 7.1.1; `NimbusJwtDecoder…restOperations(..)` and `NimbusReactiveJwtDecoder…webClient(..)` exist (JWKS via Eureka); `RestTemplate` isn't deprecated in Framework 7.0.9. The mockup's "Create account" screen has full name, college email, department, year, password.
  * Plan: own auth-service minting RS256 with `NimbusJwtEncoder` (not Spring Authorization Server); keys in `auth_db`, encrypted at rest, rotatable; hashed rotating refresh tokens with family revocation on reuse; `libs/common-security` + `libs/security-test-support`; ownership via `@eventSecurity.isOwner`; gates and volunteer assignments in event-service; gateway as an edge pre-check; a security matrix test per service; 10 exit criteria, 12 steps.
* **Notes / Errors:** A stray `python -` call in Git Bash hung (Windows Store stub) and was stopped with TaskStop; nothing changed. No processes, containers or sockets were left running. The pre-phase commit for Phase 2 still needs the owner's approval (AGENTS.md).

### 2026-10-10 — Claude (Opus 5.5) — T03: Phase 1 implemented

* **Task:** T03.1–T03.10 (Phase 1: foundation and event catalogue), following the approved `docs/plans/phase-1.md`
* **Status:** DONE (GitHub Actions run pending the next push)
* **Files:**
  * root: `pom.xml` (modules; Spotless 3.10.4 + palantir-java-format 2.102.0; compiler lint; MapStruct processor), `README.md` (quick start), `.github/workflows/ci.yml`
  * `infra/docker-compose.yml`, `infra/postgres/init.sql`
  * `libs/common-web/**` (GlobalExceptionHandler, Not-found/Conflict/BusinessRule exceptions, ProblemTypes, FieldViolation, PageResponse, CorrelationIdFilter, auto-config + tests)
  * `platform/discovery-server/**`, `platform/api-gateway/**` (routes, CorrelationIdGlobalFilter, Swagger UI + tests)
  * `services/event-service/**`: domain (Event, EventSession, Venue, Category, EventStatus, AuditableEntity), repositories + EventSpecifications, services, controllers, DTO records, MapStruct mappers, `@ValidTimeWindow`, Flyway `V1__init.sql`, `V2__reference_data.sql`, `db/seed/R__demo_seed.sql`, tests (Testcontainers)
  * docs: `docs/http/phase-1.http`, `docs/plans/phase-1.md` (§8 snippet, new §14 "As built", §15 questions resolved), `docs/DECISIONS.md` (D4 accepted, new D6 REST conventions), `PROJECT.md` (§P1 ticked + status), `log.md`
* **Details:**
  * Tests: 60, all green with `./mvnw verify` (common-web 17, discovery 1, gateway 4, event-service 38 including Testcontainers PostgreSQL 16). 0 compiler warnings.
  * Live exit demo on compose infra, jars on the project JDK: all 3 containers healthy, 5 DBs created; both apps registered in Eureka; Swagger UI at :8080 lists event-service and its OpenAPI `servers` points at :8080; create (201, `Location` on :8080), search, 400 (ProblemDetail with `errors[]` + `correlationId`), 404, 409 duplicate / non-DRAFT delete, 422 capacity, 400 bad sort all returned as expected through the gateway; correlation ids from client and gateway appear in event-service logs. `spring-boot:run` verified too. The demo-created event was deleted afterwards (DB back to the 6 seed events).
  * Problems found and fixed while building: `PropertyReferenceException` moved to `org.springframework.data.core` (Spring Data 4.1); ambiguous Mockito `delete(any())` (new `DeleteSpecification` overload); gateway sent `X-Correlation-Id` twice (now set in `beforeCommit`); Hibernate Validator 9 HV000271 (moved `@Valid` to the list element); handled 4xx weren't logged (one INFO line added); springdoc in the gateway shadows `/v3/api-docs/**` (proxied docs moved to `/docs/event-service`). All recorded in `docs/plans/phase-1.md` §14.
  * `.gitignore` reviewed (AGENTS.md mid-run rule): `target/`, `.tools/` ignored; no dependency files untracked.
* **Notes / Errors:**
  * Stopping a background `spring-boot:run` task left Maven (PID 49464) and the forked Eureka JVM (PID 15548) running; both were killed by PID. All MITPass processes stopped, `docker compose down` done (volumes kept), Docker Desktop stopped at the end.
  * A Node process (`server/src/main.ts`, not part of this repo) was listening on :8080 after the run. Left alone; it will block the gateway while it runs.
  * `.vscode/settings.json` gained `java.compile.nullAnalysis.mode` from the IDE (not this agent); left as is and included in the commit.
  * Post-phase commit: the owner approved `Phase 1: foundation and event catalogue` (no push).

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

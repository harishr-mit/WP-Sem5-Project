# Phase 8 plan: Hardening, Observability & Showcase

Status: **planned 2026-10-24, not started.** No code before the owner's go and the pre-phase commit. Owner answers: §17. Task ids T10.1–T10.20 in `log.md`.

---

## 1. Goal and exit criteria

Make MITPass robust, measurable and presentable (PROJECT.md §P8), and close the review-later items the owner pulled into this phase (RL1, RL2, RL3, RL10, RL12, RL13).

**Exit criteria (all must hold):**

1. A **clean clone** runs `docker compose up --build` at the repo root. Every service gets healthy, the bulk seed loads (one fest, 2,000 students, volunteers, passes issued through the real flow), and nothing is installed on the host apart from Docker.
2. Grafana (provisioned) shows requests/s, p95 latency, scans/min by result, the registration rate, outbox lag, DLQ size and circuit-breaker state. Zipkin shows **one scan as one trace**: portal → gateway → checkin-service → (RabbitMQ `AttendanceMarked`) → pass-service.
3. **Stopping pass-service during scanning** degrades gracefully: known passes keep scanning (local copy, D14), and unknown-pass fetches fail fast through an open circuit breaker. Grafana and Zipkin both show it.
4. `/login` and `/scans` are rate limited at the gateway (Redis); over the limit → `429` ProblemDetail with `Retry-After`.
5. `./mvnw -Pe2e verify` (e2e module) runs register → pass → scan → attendance against the compose stack, green locally and in its CI job.
6. The Gatling report (1,000 arrivals in 10 min over 4 gates, plus a doors-open peak) is in `docs/load-test/`.
7. The RL features work and are tested: waitlist setting (RL1), undo-LIVE refused once checked in (RL3), admin overrides and ban (RL10), void check-in (RL12), trigram search for users and events.
8. `docs/DEMO_SCRIPT.md` runs end to end on a clean clone: the agent does it first, then the owner live (including the RL14 two-phone check).
9. Full `./mvnw verify` green; every new endpoint has its `SecurityMatrixTest` row.

## 2. Starting point and scope

**Already there (P1–P7):** actuator in every service; Testcontainers tests in all five services and `common-messaging`; `mitpass.dlq.messages` gauge (RL4); timeouts on checkin-service's and the portal's clients; outbox `headers` JSONB that already carries `correlationId`; `User.enabled`, which login already checks (separate from email verification); an admin who isn't an organizer already sees every event on `/organizer/events`; `GET /api/users?q=&role=` with a `LIKE '%q%'` search; My registrations lists every registration (past ones too), each linked to its event page.

**Not there:** Prometheus, Grafana, Zipkin, tracing, JSON logs; Resilience4j; gateway rate limiting; container images and services in compose; a cross-service e2e test; Gatling; DataFaker seed; `DEMO_SCRIPT.md`, architecture write-up.

**Two facts that change PROJECT.md's wording:** a scan never calls pass-service over HTTP (it uses the local copy, D14), so the "gateway → checkin → pass" trace has to cross RabbitMQ; and all logins reach the gateway from the portal's IP, so the portal must forward the browser's IP.

**Out of scope (owner):** removing or anonymising users (ban only); undo for a single pass revocation other than re-issuing a new pass; anything else left in `docs/REVIEW-LATER.md` (RL4 (e), RL14), which is reviewed after P8.

## 3. Module layout after Phase 8

```
Dockerfile                    # NEW: multi-stage; one build stage, one JRE target per service
.dockerignore                 # NEW
docker-compose.yml            # NEW: include: infra/docker-compose.yml + services + observability
docker-compose.load.yml       # NEW: override for Gatling (higher login limit)
infra/
  docker-compose.yml          # unchanged role: infra only (dev runs jars against it)
  prometheus/prometheus.yml   # NEW: eureka_sd_configs → management ports
  grafana/                    # NEW: provisioning (datasource, dashboards/*.json)
e2e-tests/                    # NEW module (skipped unless -Pe2e)
load-tests/                   # NEW module (Gatling, Java DSL; skipped unless -Pload)
docs/
  ARCHITECTURE.md             # NEW (incl. concept → code table)
  DEMO_SCRIPT.md              # NEW (10-minute walkthrough)
  load-test/                  # NEW: kept Gatling HTML report
  http/phase-8.http           # NEW
```

No new service. New code goes into existing modules (§5–§10).

## 4. Key decisions for this phase (owner, 2026-10-24; D17 draft)

| # | Decision |
|---|---|
| K1 | Images: **one multi-stage `Dockerfile`** (Maven build stage with a BuildKit cache mount, `eclipse-temurin:21-jre` targets). Replaces PROJECT.md's `spring-boot:build-image`. |
| K2 | Compose: **root `docker-compose.yml` `include:`s `infra/docker-compose.yml`**; root `up` = everything, infra-only dev unchanged. JVM heap capped (`-XX:MaxRAMPercentage`, ~256 MB per service). |
| K3 | Seed: **`bulk-seed` Spring profile** in auth- and event-service; deterministic user ids from a shared `SeedPeople` (libs/common-security); event-service registers through `RegistrationService`, so passes and emails go through the real flow (~2,000 mails in Mailpit is accepted). On in the root compose. |
| K4 | Tracing **through RabbitMQ**: `traceparent` is stored in outbox `headers` (a new key, no migration) and restored by the relay; listeners continue the trace. |
| K5 | **Resilience4j on every internal call** (circuit breaker + timeout; one retry on idempotent GETs only). |
| K6 | Rate limits: portal sends `X-Forwarded-For`; gateway keys `/api/auth/login` by client IP, `/api/scans/**` by JWT `sub`. Spoofed XFF accepted for a demo. Values in `mitpass.gateway.rate-limit.*`. |
| K7 | **Separate management port** per service (`10000 + port`, e.g. 18081): actuator health/prometheus there, not published by compose, not routed by the gateway. Prometheus discovers targets through Eureka. |
| K8 | e2e: **`e2e-tests` module**, Testcontainers `ComposeContainer` on the root compose, `-Pe2e`, separate CI job on pull requests. |
| K9 | Load: PROJECT.md's shape **plus a doors-open peak**. |
| K10 | Rehearsal: the agent on a fresh clone first, then the owner live (incl. RL14). |
| K11 | RL1: per-event **`waitlistEnabled`, default off**. On: waitlist while open and full, and after closing until LIVE (always WAITLISTED then). Off: full → refused; closed → refused. Turning it off while anyone is WAITLISTED → 422. Migration: on only for events that have WAITLISTED rows. |
| K12 | RL2: keep the "End event" button. RL13: keep upcoming-only Explore (past registrations stay reachable from My registrations; a test pins that). |
| K13 | RL3: LIVE → REGISTRATION_CLOSED **refused (409) once any check-in exists**; event-service asks checkin-service (SERVICE token). If checkin-service doesn't answer, it is refused (503); it fails closed. |
| K14 | RL10 admin-only edges: **un-cancel** (back to the state recorded before CANCELLED), **force LIVE**, **reopen COMPLETED → LIVE**, **re-issue one revoked pass**, **edit any field in any state** (rules kept: capacity ≥ seats taken; time changes publish `EventRescheduled`), **ban / unban** a user, user list with search. Each is audited and confirmed (RL9). |
| K15 | RL10 passes: **re-issue new passes**, REVOKED and EXPIRED stay final (D12 unchanged). "One pass per registration" becomes "one non-revoked, non-expired pass per registration". |
| K16 | RL12: **void check-in** by the event's organizer or an admin; the attendance row is kept (marked void, excluded from counts and certificates), presence moves back, new `AttendanceVoided` event clears pass-service's `checkedInAt` / `usedAt`. |
| K17 | Search: **`pg_trgm`** with a generated lower-cased `search_text` column + GIN trigram index, for users (name, email, register number) and events (title, code, description). Same `q` parameter; results ranked by `similarity()`. |

## 5. Observability

- **Metrics:** `micrometer-registry-prometheus` in every service (root POM via Boot BOM). Actuator on the management port (K7) exposes `health,info,prometheus`; probes on (`management.endpoint.health.probes.enabled`). Eureka instance metadata `management.port` (Spring Cloud sets it); `AdminHealthController` reads health from that port (today it uses the instance port).
- **Custom meters:** `mitpass.scans{result}` counter (checkin-service, in the pipeline's result path), `mitpass.registrations{status}` counter (event-service), `mitpass.outbox.lag.seconds` gauge (`common-messaging`: age of the oldest unpublished row), existing `mitpass.dlq.messages{queue}`, Resilience4j's own breaker meters.
- **Grafana:** provisioned Prometheus + Zipkin data sources and one "MITPass overview" dashboard (requests/s, p95 per service, scans/min by result, registrations/min, outbox lag, DLQ size (RL4), breaker state).
- **Tracing:** Micrometer Tracing → Zipkin (100 % sampling, `mitpass`-tunable). Rabbit template and listener observation on. The outbox relay can't use the request's context (it runs later on its own thread), so `OutboxPublisher` stores `traceparent` in `headers` and `OutboxRelay` publishes inside a span whose parent comes from it.
- **JSON logs:** Boot's structured logging (`logging.structured.format.console=ecs`), switched on by env in compose only (plain text when running jars locally). MDC already has `correlationId` and the user; tracing adds `traceId` / `spanId`.

## 6. Resilience

- `spring-cloud-starter-circuitbreaker-resilience4j` (Spring Cloud BOM), used programmatically (`CircuitBreakerFactory.create(name).run(call, fallback)`): no AOP, keeps the clients' existing error handling.
- Breakers, one per target: checkin→pass (`HttpPassDirectory`, `PassJwksCache`, revocation feed), checkin/pass/notification→event (`HttpEventCatalog`, `EventCatalogClient`, `HttpClients`), event→auth (`AuthServiceClient`), event→checkin (new, RL3), portal→gateway (`GatewayClient`: an open breaker shows the "service unavailable" error page fast). Timeouts on every client (some have none today).
- Fallbacks keep today's behaviour where one exists (the JWKS cache keeps its last keys; the reconciler skips the run); a scan of an unknown pass while pass-service is down answers `UNKNOWN_PASS` without waiting.
- Settings in `application.yml` under `resilience4j.*` (failure rate, wait in open state); the breaker names are listed in D17.

## 7. Gateway rate limiting

- `spring-boot-starter-data-redis-reactive` in the gateway; `RequestRateLimiter` filter (Redis token bucket) on the login route (key resolver: first `X-Forwarded-For` value, else remote address) and the scan routes (key: JWT `sub`). Defaults: login 10/min, burst 5; scans 10/s, burst 20 (`mitpass.gateway.rate-limit.*`).
- The 429 body is a ProblemDetail (D6) with `Retry-After`; the portal shows "too many attempts, try again in N s" on the login page and the scanner shows an amber result.
- Portal: `GatewayClient` adds `X-Forwarded-For: <browser IP>` (from the current request) on the login call.
- Redis down → the limiter allows the request (Spring's default; logged), since a dead counter store must not stop logins or scans.

## 8. Review-later features (RL1, RL3, RL10, RL12) and search

**RL1 waitlist (event-service V9):** `waitlist_enabled BOOLEAN NOT NULL DEFAULT FALSE`, then `UPDATE … SET TRUE WHERE EXISTS (WAITLISTED)`. `EligibilityPolicy` / `RegistrationService`: REGISTRATION_CLOSED + waitlist on → WAITLISTED (never a seat, even if one is free, because promotion handles freed seats in order); off + full → 422 `EVENT_FULL`. Edit to off with waiters → 422. DTOs gain `waitlistEnabled` (additive). Portal: checkbox on the event form; event page text per case (both bundles). Promotion after close keeps working (it is a cancellation path).

**RL3 (event-service + checkin-service):** checkin-service `GET /api/checkins/events/{id}/has-check-ins` (or the existing summary, if its shape fits) allows SERVICE; event-service gets a SERVICE client (`mitpass.auth.service.clients` += event-service) and calls it in `EventLifecycleService` before LIVE → REGISTRATION_CLOSED. RL3's note in REVIEW-LATER is updated.

**RL10 admin edges (event-service):** `EventStatus` gains an admin-only table: CANCELLED → {PUBLISHED, REGISTRATION_OPEN, REGISTRATION_CLOSED} (only the one recorded in `event_state_history` before CANCELLED), REGISTRATION_CLOSED → LIVE (force; sets `startsAt` to now if later, so passes unlock through `EventRescheduled`), COMPLETED → LIVE (reopen; requires `endsAt` in the future, else 422). `allowedTransitions` includes them only for ADMIN. `EventService.update` skips the per-state edit lock for ADMIN (`isEditable()` stays the organizer rule); capacity ≥ seats taken stays. History rows record trigger `ADMIN`. `EventStateChanged` is unchanged (it already carries `fromStatus`).

**RL10 passes (pass-service V3):** the unique "one pass per registration" constraint becomes a partial unique index over non-revoked, non-expired passes. On `EventStateChanged(from=CANCELLED)` or `(from=COMPLETED, to=LIVE)`: issue a new pass for each registration whose latest pass was revoked with `EVENT_CANCELLED` or expired by the event end, if the registration is still confirmed (pass-service knows it from its own pass rows). `POST /api/passes/{id}/reissue` (ADMIN): new pass for that revoked pass's registration. New passes publish `PassIssued` as usual (checkin copy, email).

**RL10 ban (auth-service):** `POST /api/users/{id}/ban` and `/unban` (ADMIN): `enabled=false` + refresh-token families revoked (existing `DELETE …/sessions` logic); refresh refused while banned. Can't ban yourself or the last admin (422). The current access token runs out within 15 min (documented). Portal: Admin · Users gains Ban/Unban with a confirmation.

**RL12 void check-in (checkin-service V3):** `attendance.voided_at`, `voided_by`, `void_reason`. `POST /api/attendance/{id}/void` (event organizer via the existing ownership lookup, or ADMIN; reason required). Counts, CSV, certificates and the dashboard skip void rows; presence goes back to NOT_ARRIVED (or OUT if another session's attendance remains); the Redis counters are rebuilt for that event. A new contract `AttendanceVoided` via the outbox; pass-service clears `checkedInAt` / `usedAt`. The unique `(pass_id, session_id)` must allow a re-check-in after a void, so it becomes a partial unique index over non-void rows. Portal: a Void button on the organizer's attendee list, with confirmation (RL9).

**Search (auth V5, event V10):** `CREATE EXTENSION IF NOT EXISTS pg_trgm`; `search_text` as a `GENERATED ALWAYS AS (lower(coalesce(a,'') || ' ' || …)) STORED` column (not `concat_ws`, which isn't immutable); `GIN (search_text gin_trgm_ops)`. `UserSpecifications` / event specifications use `search_text LIKE %q%` (the trigram index serves a leading wildcard) and order by `similarity(search_text, q)` when `q` is present. A test with ~2,000 rows asserts `EXPLAIN` uses the index.

## 9. Ops: images, compose, seed

- **Dockerfile:** `build` stage (`eclipse-temurin:21-jdk`, copies the repo, `./mvnw -B -ntp package -DskipTests` with `--mount=type=cache,target=/root/.m2`); one `FROM eclipse-temurin:21-jre AS <service>` target per service copying its jar. BuildKit runs the shared build stage once for all targets.
- **Root compose:** `include: infra/docker-compose.yml`; services with `build: {context: ., target: …}`, env for hosts (`postgres`, `rabbitmq`, `redis`, `mailpit`, `discovery-server`), `depends_on: condition: service_healthy`, healthchecks on `/actuator/health/readiness` (management port). Published: 8080 gateway, 8090 portal, 8443 portal `lan` (cert mounted from `infra/certs` if present), 8761, 3000 Grafana, 9090 Prometheus, 9411 Zipkin, plus the infra UIs it already publishes. Prometheus, Grafana and Zipkin are added here.
- **Seed (`bulk-seed` profile):** auth-service inserts 2,000 verified students (DataFaker names, departments, years; shared BCrypt hash of `Passw0rd!`, D8) and ~40 volunteers; event-service creates one fest (~12 events, 4 gates, sessions), assigns volunteers, and registers students through `RegistrationService` (some events over capacity, to show waitlists). Idempotent: skipped if the fest code exists. User ids: `SeedPeople.id(n)` (name-based UUID), the same in both services.

## 10. Tests and load

- **e2e-tests:** one JUnit class, `ComposeContainer` on the root compose (no seed profile; it signs up its own student through Mailpit's API), drives the gateway: sign up → verify → register → poll for the pass → live token → volunteer scan → ACCEPTED → attendance summary shows 1; second scan → ALREADY_CHECKED_IN. CI: new job `e2e` on `pull_request` (`./mvnw -B -ntp -Pe2e -pl e2e-tests -am verify`).
- **load-tests:** Gatling Java DSL, `gatling-maven-plugin` (pinned in the root POM), `-Pload`. Scenario: seeded students log in, fetch a live QR, and one of 4 gate volunteers scans it; 1,000 arrivals over 10 min, plus 300 in the first minute. Run against the root compose plus `docker-compose.load.yml` (raises the login limit). Report copied to `docs/load-test/`.
- **Per-feature tests:** listed per step in §15 ("done when").

## 11. Configuration additions (all under `mitpass.*` unless a library owns the key)

```yaml
management.server.port: 1${server.port}     # K7 (literal per service: 18081 …)
mitpass.tracing.sampling: 1.0               # mapped to management.tracing.sampling.probability
mitpass.gateway.rate-limit.login: {replenish-per-minute: 10, burst: 5}
mitpass.gateway.rate-limit.scans: {replenish-per-second: 10, burst: 20}
mitpass.seed.bulk: {students: 2000, volunteers: 40, fest-code: FEST26}
resilience4j.circuitbreaker.configs.default: {failure-rate-threshold: 50, wait-duration-in-open-state: 10s, sliding-window-size: 10}
# auth-service: mitpass.auth.service.clients += event-service (SERVICE, for RL3)
```

## 12. Ports

Unchanged service ports. Management: 10000 + port (18761, 18080, 18081–18085, 18090). Grafana 3000, Prometheus 9090, Zipkin 9411.

## 13. Security matrix additions

ban/unban (auth), reissue (pass), void (checkin), has-check-ins (checkin, SERVICE only), admin-only transitions (event; the matrix gets an "admin edge" row per edge, organizer → 409/403), the rate-limited routes (gateway test: 429 after the burst). Management ports: a test that `/actuator/prometheus` is open on the management port and absent on the main port.

## 14. Documentation to update at T10.19

`PROJECT.md` (§1 note, §3 tables: `AttendanceVoided`, `waitlistEnabled`; §4 Ops row; §P8 ticks/status; §6 demo script points at `DEMO_SCRIPT.md`), `docs/DECISIONS.md` D17 (+ D10/D12/D14 notes for RL changes), `README.md` (one-command quick start, ports, `down -v` note: none needed, no new database), `docs/security-matrix.md`, `docs/http/phase-8.http`, `docs/REVIEW-LATER.md` (RL1–RL3, RL10, RL12, RL13 → RESOLVED with outcomes), `AGENTS.md` parts table (Docker/compose/observability rows, e2e and load modules), this plan's §18.

## 15. Steps (log.md T10.1–T10.20)

Each step ends with its module's `./mvnw -pl <module> -am verify` green; "done when" adds the specific check.

| Step | Work | Done when |
|---|---|---|
| T10.1 | Record owner decisions: PROJECT.md §P8 + §3, D17 draft, REVIEW-LATER notes | docs reviewed by the owner (no code) |
| T10.2 | Management ports, prometheus endpoint, probes, Eureka metadata; admin health follows | admin health test green; prometheus reachable on 18xxx only |
| T10.3 | Tracing + AMQP observation + outbox `traceparent`; JSON logs via env | common-messaging test: relayed message carries the stored trace id; consumer span has it as parent |
| T10.4 | Custom meters (scans, registrations, outbox lag) | meter tests in checkin/event/common-messaging |
| T10.5 | Resilience4j on every internal client | per client: breaker opens after failures, fallback answers fast (WireMock-free: stub server or mocked RestClient) |
| T10.6 | Gateway rate limits + portal XFF + 429 ProblemDetail + portal messages | gateway Testcontainers Redis test: burst+1 → 429; portal test for XFF and message |
| T10.7 | Trigram search (auth V5, event V10) | EXPLAIN test uses the GIN index; existing search tests green |
| T10.8 | RL1 waitlist setting (event V9, portal form/texts) | rule tests for each case; concurrency test still exact (with waitlist on) |
| T10.9 | RL3 has-check-ins + SERVICE client + refusal | lifecycle test: undo refused after a check-in, 503 when checkin is down |
| T10.10 | RL10 event-service admin edges + admin edits + portal buttons | transition table tests; organizer gets 409, admin succeeds; audit rows |
| T10.11 | RL10 pass-service re-issue (V3 index, saga reverse, single re-issue) | un-cancel → new passes once (idempotent on redelivery); reissue endpoint test |
| T10.12 | RL10 ban/unban + portal | login and refresh refused while banned; self / last admin → 422 |
| T10.13 | RL12 void check-in (checkin V3, `AttendanceVoided`, pass-service handler, portal) | void excluded from reports; re-check-in allowed after void; pass fields cleared |
| T10.14 | Grafana dashboard + Prometheus config | dashboard JSON loads; panels show data on the running stack |
| T10.15 | Dockerfile, `.dockerignore`, root compose, healthchecks | fresh `docker compose up --build`: all healthy; portal journey works |
| T10.16 | `bulk-seed` profile (DataFaker, `SeedPeople`) | 2,000 students, passes issued, re-run is a no-op |
| T10.17 | e2e-tests module + CI job | `-Pe2e` green locally; CI job green on the PR |
| T10.18 | load-tests (Gatling) + report | report in `docs/load-test/`; no 5xx; p95 recorded |
| T10.19 | Docs (§14), ARCHITECTURE.md with the concept → code table, DEMO_SCRIPT.md, Playwright `p8.mjs` for changed pages | every doc in §14 updated; Playwright checks green (360 px + desktop, light + dark) |
| T10.20 | Rehearsal: fresh clone in scratch → whole DEMO_SCRIPT; then owner live run + RL14 | agent report written in log.md; owner's run recorded |

Commits: the owner approves each one (pre-phase commit of this plan, a mid-run commit after T10.13 if wanted, the end-of-phase commit).

## 16. Gotchas to check at build time

1. Boot 4's tracing modules (`spring-boot-micrometer-tracing-*`, Zipkin reporter) and their starter names; check against the Boot 4.1.1 BOM, don't guess.
2. Spring Security and a separate management port: confirm the management child context isn't behind the JWT filter chain (otherwise add a chain for it).
3. `eclipse-temurin:21-jre` may lack `curl` for healthchecks; fall back to `wget` or install curl in the runtime stage.
4. `mvnw` inside Linux build stage: needs LF endings (D5) and runs as `sh mvnw` to avoid the executable bit.
5. Docker Desktop memory: ~9 JVMs + infra + observability. Check the default limit; document a minimum in README.
6. `pg_trgm` must be created by the database owner (trusted extension since PG 13); Testcontainers' postgres image includes it.
7. Re-issue vs the existing unique constraint: drop and replace in one migration; existing data already satisfies the partial index.
8. Force LIVE moves `startsAt`, so the reschedule email goes out; acceptable (it's true), note it in DEMO_SCRIPT.
9. Gatling login volume vs the login limit (K6): the load override file raises it; the default stays strict.
10. Redis counters after a void: rebuild for one event, not the whole cache.

## 17. Owner questions

**Answered 2026-10-24:** K1–K17 above (images; compose; seed via profile with real emails; tracing through RabbitMQ; Resilience4j everywhere; XFF + user keys; management ports; e2e module + CI job; plan + peak load; agent then owner rehearsal; RL1 waitlist setting off by default, refuse off with waiters, on only where waitlisted today; RL2 keep; RL3 refuse; RL13 keep; RL10 re-issue, force LIVE, reopen, single re-issue, edit any state with rules kept, ban only, search; RL12 organizer + admin; trigram for users and events).

**Defaults the owner can still change before the pre-phase commit:**

1. **Q1: Rate-limit values:** login 10/min burst 5 per IP; scans 10/s burst 20 per user.
2. **Q2: Reopen and expired passes:** reopening a COMPLETED event re-issues passes that expired at the event's end (same mechanism as un-cancel).
3. **Q3: Force LIVE sets `startsAt` to now** (so passes unlock); the reschedule email is sent.
4. **Q4: RL3 fails closed:** checkin-service unreachable → undo refused (503).
5. **Q5: Seed size:** 1 fest, ~12 events, 4 gates, 2,000 students, 40 volunteers.
6. **Q6: Void check-in reason** is required (free text, max 200 chars).
7. **Pre-phase commit:** the owner commits this plan (`chore: phase 8 plan`) before T10.1.

## 18. As built

*(filled in during the phase)*

# Phase 3 plan: Registration & Transactional Core

> **Source:** `PROJECT.md` §P3 (including the portal slice added by T21 and `docs/DECISIONS.md` D9). **Tracked in:** `log.md` as T05, sub-tasks T05.1–T05.12 (same numbering as the steps in §15).
> **Written:** 2026-10-15, after Phase 2 was merged to `main` (CI green). This plan is a guide: if a step turns out simpler or different in practice, do the sensible thing and record the deviation in `log.md` and in a closing "As built" section here.

## 1. Goal and exit criteria

**Goal:** students register for events without overbooking, even under heavy concurrency. Events and registrations move only along their state machines, and every fact another service needs leaves event-service reliably through a transactional outbox to RabbitMQ. A thin `web-portal` shows all of it in a browser.

**Exit demo** (all must pass before T05 is marked done):

1. `discovery-server`, `auth-service`, `event-service`, `api-gateway` and `web-portal` start on compose infra (PostgreSQL, RabbitMQ, Mailpit). Eureka lists the five apps. The RabbitMQ management UI (http://localhost:15672) shows the topic exchange `mitpass.events` and the dev tap queue `mitpass.events.tap`.
2. **Event lifecycle:** organizer A moves a new event DRAFT → PUBLISHED → REGISTRATION_OPEN with `POST /api/events/{id}/transitions`. An illegal move (DRAFT → LIVE) gets `409 illegal-transition`, and the body lists `allowedStates: ["PUBLISHED"]`. `GET /api/events/{id}/history` lists every move with who, when and why. An event whose `registrationClosesAt` passes closes by itself within one scheduler interval, and its history row says `SCHEDULER`.
3. **Concurrency test, run live** (`SeatClaimConcurrencyTest`): 500 parallel registrations for 100 seats give exactly 100 CONFIRMED and 400 WAITLISTED, waitlist positions 1–400 with no gaps or repeats, `seats_taken == 100`, and exactly 100 `RegistrationConfirmed` outbox rows. The naive version (test code only) overbooks on the same setup, and the test prints both results side by side.
4. **Waitlist promotion:** on the 1-seat demo event, student 1 is CONFIRMED and student 2 is WAITLISTED #1. Student 1 cancels, and student 2 is CONFIRMED by the same transaction. The tap queue shows `RegistrationCancelled`, `WaitlistPromoted` and `RegistrationConfirmed` (`viaPromotion: true`), each carrying the request's correlation id.
5. **Idempotency:** the same `POST /api/registrations` with the same `Idempotency-Key` twice returns the same `201` body (second response has `Idempotent-Replayed: true`), and only one registration exists. The same key with a different body gives `422 idempotency-key-mismatch`. A second POST without a key gives `409 already-registered`.
6. **Eligibility:** a student outside the event's departments or years gets `422 not-eligible` with the reasons listed. `GET /api/events/{id}/eligibility` tells the logged-in student the same thing before they try. Registering after `registrationClosesAt` gives `409 registration-closed`, even if the scheduler hasn't run yet.
7. **Optimistic locking:** two organizer edits sent with the same `version` → the second gets `409` with a "reload" hint. Lowering capacity below the seats already taken gives `422`. Raising capacity on a full event promotes that many students from the waitlist.
8. **Portal (D9 slice) at http://localhost:8090:** sign up (verify through Mailpit), log in, browse the catalogue, open an event (seats left and "You're eligible / not eligible because …"), register → CONFIRMED. A second student on the 1-seat event sees WAITLISTED #1. The first cancels, and the second student's "My registrations" page shows CONFIRMED after a reload. Organizer A's "My events" page shows buttons only for the allowed transitions, and pressing one changes the status. The page source never contains a token, and a form POST without its CSRF token gets 403.
9. The security matrix (event-service) covers every new endpoint, all cells green, and `docs/security-matrix.md` is updated. Swagger UI at :8080 shows the new endpoints.
10. `./mvnw verify` is green locally and in GitHub Actions.

## 2. Starting point and scope

**Built in Phases 1–2:** `libs/common-web` (D6), `libs/common-security` + `libs/security-test-support` (D7), discovery, gateway (JWT pre-check), auth-service (sign-up, login, refresh, JWKS, user admin), event-service (catalogue, ownership, gates, volunteers). `EventStatus` exists but only as a stored value (every event starts DRAFT; the seed sets other states directly). `Event` already has `@Version`, and `PUT /api/events/{id}` already rejects a stale `version` with 409. compose already runs RabbitMQ 4 (management UI on :15672), and `event_db` exists, so **no `docker compose down -v` is needed** in this phase.

**Defaults this plan assumes** (owner questions in §17; change them before T05.2 starts):

| Question | Default |
|---|---|
| One registration per student per event | **One *active* registration** (partial unique index on `(event_id, user_id)` where the status is PENDING, CONFIRMED or WAITLISTED). A student who cancelled can register again and gets a new registration (and in P4 a new pass). PROJECT.md says "unique `(event_id, user_id)`"; this keeps that for active rows and keeps the cancelled ones as history |
| Time-based transitions | The scheduler does all four time-based moves: open registration at `registrationOpensAt`, close it at `registrationClosesAt` (or `startsAt`), go LIVE `doors-open-lead` (30 min) before `startsAt`, COMPLETED at `endsAt`. Organizers can also press each button early |
| Registration window | **The state is the gate.** Registration needs status REGISTRATION_OPEN and, as a domain check, `now < registrationClosesAt`. An organizer may open early by hand, so `registrationOpensAt` only drives the scheduler |
| Event cancellation | Registrations are left as they are; the event's status says CANCELLED, and new registrations and cancellations are refused (409). P4's saga revokes the passes from `EventStateChanged` |
| `UserRegistered` | auth-service publishes it through the same outbox (phase-2 plan §2 deferred it to P3). Nothing consumes it until P7 |

**Out of scope (and where it lands):**

| Not in P3 | Phase | What P3 does instead |
|---|---|---|
| Consuming events, `processed_message` table, idempotent consumer helper | P4 (pass-service) | The envelope carries a stable `messageId` (= outbox id) for P4 to deduplicate on; the dev tap queue makes messages visible |
| Dead-letter queues, retry policy on consumers | P7 | The relay retries unpublished rows on every poll and records the last error |
| ShedLock for schedulers | P7 | Each scheduled transition is its own transaction guarded by `@Version`, so two instances can't apply the same move twice |
| Per-session registration | — | Registration is for the whole event; sessions matter for attendance in P5 |
| Emails for confirmations / promotions | P7 | The events exist on RabbitMQ; notification-service consumes them later |
| Portal polish, token refresh, HTMX, custom error pages | P6 | D9 shortcuts (§10) |
| Load test over HTTP | P8 (Gatling) | The concurrency test runs the real service layer against real PostgreSQL |

## 3. Module layout after Phase 3

```
pom.xml                              # <modules>: + libs/common-messaging, web-portal
libs/common-web/                     # + IllegalTransitionException (409 with allowedStates), LifecycleState helper
libs/common-messaging/               # NEW: outbox writer (JdbcClient), relay to RabbitMQ, envelope, event contracts
services/event-service/              # + lifecycle, scheduler, registrations, waitlist, eligibility, idempotency, outbox
services/auth-service/               # + UserRegistered via the outbox (one migration, one call in SignupService)
platform/api-gateway/                # + route /api/registrations/**
web-portal/                          # NEW :8090, Spring MVC + Thymeleaf BFF (D9 slice)
docs/http/phase-3.http               # NEW demo requests
```

| Module | Base package | Key dependencies (artifactIds; versions from the Boot / Cloud BOMs) |
|---|---|---|
| `common-messaging` | `com.mitpass.common.messaging` | `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`, `spring-boot-autoconfigure`; test: `spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-rabbitmq`, `testcontainers-junit-jupiter`, `postgresql` |
| `event-service` (adds) | | `common-messaging`; test: `testcontainers-rabbitmq` (only the messaging integration test) |
| `auth-service` (adds) | | `common-messaging` |
| `web-portal` | `com.mitpass.portal` | `spring-boot-starter-webmvc`, `-thymeleaf`, `-security`, `-validation`, `-restclient`, `-actuator`, `thymeleaf-extras-springsecurity6`, `spring-security-oauth2-jose` (Nimbus, to read token claims), eureka client; test: `-webmvc-test`, `-security-test`, `-restclient-test`. **Not** `common-web`: its `@RestControllerAdvice` would answer HTML pages with JSON |

All of these artifacts are in the Boot 4.1.1 / Testcontainers 2.0.5 BOMs (checked in `~/.m2` on 2026-10-15).

**event-service package additions:**

```
com.mitpass.event
├── domain/        EventStatus (+ transition table), EventStateChange, Registration, RegistrationStatus,
│                  RegistrationTransition, Eligibility (value object)
├── repository/    EventStateChangeRepository, RegistrationRepository (+ seat-claim / lock queries)
├── service/       EventLifecycleService, EventLifecycleScheduler, EligibilityPolicy,
│                  RegistrationService (register, cancel, promote), WaitlistService
├── idempotency/   IdempotencyRecord, IdempotencyStore (claim / complete / replay), IdempotencyCleanup
├── messaging/     EventServiceEvents (builds the contract records and calls OutboxPublisher)
├── security/      + RegistrationSecurity (self / event owner / admin)
└── web/           + LifecycleController, RegistrationController, EligibilityController, dto/...
```

## 4. Key decisions for this phase

| Topic | Choice | Why |
|---|---|---|
| State machine style | Each status enum implements `LifecycleState<S>` (`allowedNext()`), defined once as an `EnumMap` table. One `transition(to, actor, reason)` method per aggregate calls `LifecycleState.require(from, to)`, which throws `IllegalTransitionException` (common-web) → `409 illegal-transition` with `currentState`, `requestedState`, `allowedStates` | PROJECT.md §3: one transition method per aggregate, backed by an enum table. In common-web because pass-service (P4) and checkin-service (P5) reuse it |
| Event transitions API | `POST /api/events/{id}/transitions {to, reason?}`, plus `allowedTransitions` in `EventDetailResponse` | One endpoint for every button; the portal renders buttons straight from `allowedTransitions`, so it can't offer an illegal move |
| History | `event_state_history` and `registration_history`: one row per transition (from, to, when, who, `trigger` MANUAL/SCHEDULER/SYSTEM, reason). Creating an event writes `null → DRAFT` | "Transition history is audited" (PROJECT.md §1 row 9) |
| Scheduler | `EventLifecycleScheduler` `@Scheduled(fixedDelayString = "${mitpass.events.scheduler.interval}")`, off in tests. It queries due events, then applies each move through `EventLifecycleService` in its **own transaction**; an `OptimisticLockingFailureException` or `IllegalTransitionException` skips that event (logged at DEBUG) | A second instance, or an organizer pressing the button at the same moment, can't double-apply a move. ShedLock waits for P7 |
| Edits by state | Everything editable in DRAFT and PUBLISHED. From REGISTRATION_OPEN on, `code` is frozen (P4 pass numbers contain it). LIVE, COMPLETED and CANCELLED events are read-only (409). Capacity may never go below `seats_taken` (422); raising it on an open or closed event promotes from the waitlist in the same transaction | Edits can't break registrations already made |
| Seat counter | `event.seats_taken` (CHECK `0 <= seats_taken <= capacity`), mapped **read-only** in JPA (`insertable = false, updatable = false`) and changed only by `@Modifying` queries | A normal entity save (organizer edit) can never overwrite the counter with a stale value; the CHECK is the last line of defence |
| Seat claim (fast path) | `UPDATE event SET seats_taken = seats_taken + 1 WHERE id = :id AND status = 'REGISTRATION_OPEN' AND seats_taken < capacity`. 1 row → CONFIRMED. No `@Version` bump | PROJECT.md's conditional update. The row lock it takes is held until commit, so claims on one event serialize briefly; no lost updates, no overbooking |
| Waitlist (slow path) | 0 rows → lock the event row (`@Lock(PESSIMISTIC_WRITE)`, `SELECT … FOR UPDATE`), re-check status and seats (a cancellation may have freed one meanwhile), then either claim the seat or insert WAITLISTED with `waitlist_seq = nextval('registration_waitlist_seq')` | Shows the second locking strategy. Without the lock, a cancellation committing between "full" and "insert WAITLISTED" would leave a free seat next to a waiting student |
| Cancel + promote | Cancelling a CONFIRMED registration locks the event row first, then takes the lowest-`waitlist_seq` WAITLISTED row (`FOR UPDATE`) and confirms it, moving the seat directly (`seats_taken` unchanged); with nobody waiting it decrements `seats_taken`. All in one transaction with the cancellation, both history rows and the outbox rows | PROJECT.md: promotion "in the same transaction". The event-row lock orders it against the slow path above |
| Invariant | Per event: `seats_taken == count(CONFIRMED)` and no WAITLISTED row while `seats_taken < capacity` (unless registration is closed and nobody cancelled). Asserted by the concurrency tests | The thing the demo proves |
| Waitlist position | Not stored: `count(WAITLISTED with waitlist_seq <= mine)`. Index `(event_id, status, waitlist_seq)` | A stored position would need rewriting on every cancellation |
| PENDING | A registration is inserted as PENDING and moves to CONFIRMED or WAITLISTED **in the same transaction**, so outsiders never see PENDING; its history keeps the first step | PROJECT.md's state machine; leaves room for approval-based events later |
| Isolation | PostgreSQL default READ COMMITTED everywhere; correctness comes from the conditional update, row locks and constraints, not from SERIALIZABLE | Fewer retries; each guarantee has a named mechanism the demo can point at |
| Who registers | `hasRole('STUDENT')`, for themselves only (`sub` from the token). Cancel: the student themselves, the event's organizer, or ADMIN (`@registrationSecurity`) | Volunteers and organizers who also study have the STUDENT role too |
| Snapshot on the row | `user_name`, `user_email`, `department`, `year_of_study` copied from the token at registration | Organizer lists and P4's pass need them without calling auth-service |
| Eligibility | `EligibilityPolicy.check(event, user, now)` returns `Eligibility {eligible, reasons[]}`. Rules: department in `eligible_departments` (empty = all), `min_year <= year <= max_year` (a restriction with no `year` claim = not eligible), status REGISTRATION_OPEN and `now < registrationClosesAt`. Used by both `POST /api/registrations` and `GET /api/events/{id}/eligibility` | Domain layer (AGENTS.md workflow step 4); the portal can show the reason before the student clicks |
| Department codes on events | Validated by format only (`^[A-Z]{2,16}$`); the portal offers auth-service's `GET /api/departments` list | event-service doesn't own departments, and a REST call on every event edit isn't worth it |
| Idempotency-Key | Optional header on `POST /api/registrations` (1–80 chars, `[A-Za-z0-9_-]`). `idempotency_record` PK `(user_id, idem_key)`, so keys are scoped per user. In the **same transaction** as the registration: `INSERT … ON CONFLICT DO NOTHING`. Inserted → do the work, store status + body + `Location`. Not inserted → the other request has committed (PostgreSQL waits for it), so compare the request hash and replay (`Idempotent-Replayed: true`), or `422 idempotency-key-mismatch`. Failed requests roll the key back with everything else, so a retry runs again. TTL 24 h, cleaned up hourly | Atomic with the business row: a crash after commit can't lose the stored response, and two concurrent retries can't both register |
| Where the idempotency code lives | event-service (`idempotency` package) | Only one service needs it in P3. It moves to a lib when a second one does (AGENTS.md: shared code in `libs/`) |
| Outbox storage | `libs/common-messaging` writes with **`JdbcClient`** to an `outbox_message` table that each service creates in its own Flyway migration (the lib ships the reference DDL and a test that runs it). `OutboxPublisher.publish(…)` is `@Transactional(propagation = MANDATORY)` | JDBC avoids entity scanning across modules; `JpaTransactionManager` exposes the same connection, so the row commits or rolls back with the business change. MANDATORY makes a call outside a transaction fail loudly |
| Relay | A `SmartLifecycle` bean with its own single-thread scheduler polls every 500 ms: `SELECT … WHERE published_at IS NULL ORDER BY occurred_at LIMIT 100 FOR UPDATE SKIP LOCKED`, publishes each message with **publisher confirms**, sets `published_at`. Failure → `attempts + 1`, `last_error`, retried next poll. Published rows are deleted after 7 days. Off in tests except the messaging integration tests | At-least-once delivery; several instances can run side by side (SKIP LOCKED). Its own scheduler means the lib doesn't switch on `@EnableScheduling` in every app, and it stops cleanly on shutdown |
| Topology | Topic exchange `mitpass.events` (durable), routing key = event type in dot case (`registration.confirmed`). The producer declares only the exchange; consumers declare their own queues (P4+). Dev only: queue `mitpass.events.tap` bound to `#`, `x-max-length` 1000 (`mitpass.messaging.tap-queue.enabled`, on in `application.yml`, off in tests) | Something to look at in the management UI before any consumer exists, which can't grow without limit |
| Message format | AMQP properties: `messageId` = outbox id, `type` = event type, `contentType` `application/json`, headers `correlationId`, `schemaVersion`. Body = envelope `{messageId, type, schemaVersion: 1, occurredAt, aggregateType, aggregateId, correlationId, payload}` | P4 deduplicates on `messageId`; correlation ids follow a request across services (P8 tracing builds on it) |
| Event contracts | Java records in `common-messaging` (`com.mitpass.common.messaging.contract`): `RegistrationConfirmed`, `RegistrationCancelled`, `WaitlistPromoted`, `EventStateChanged`, `UserRegistered`. Fields are only ever added (AGENTS.md); consumers ignore unknown fields | One definition for producer and consumers in this monorepo |
| Promotion emits two events | `WaitlistPromoted` (for the "you're in" email in P7) **and** `RegistrationConfirmed` with `viaPromotion: true` | pass-service (P4) listens to `RegistrationConfirmed` only, so every confirmed seat gets a pass by one rule |
| Portal auth | Spring Security `formLogin` with a custom `AuthenticationProvider` that calls `POST /api/auth/login` through the gateway. Tokens go into a session-scoped `PortalTokens` object; the principal `PortalUser` is built from the access token's claims (read with Nimbus, not verified: the portal just received it from auth-service over its own call, and every backend verifies it again). 401 `invalid-credentials` → `BadCredentialsException`, 403 `email-not-verified` → `DisabledException`, 423 → `LockedException` | BFF rule (D9): the browser only ever holds the `JSESSIONID` cookie (HttpOnly) |
| Portal session expiry | A filter checks `PortalTokens.expiresAt`; past it (or on a 401 from the gateway) the session is invalidated and the user is sent to `/login?expired`. Logout also calls `POST /api/auth/logout` | D9 shortcut: re-login after 15 min, automatic refresh in P6 |
| Portal double-submit | The event page renders a fresh `Idempotency-Key` in a hidden field; `POST /events/{id}/register` forwards it. A double click replays instead of failing | Shows PRG and idempotency working together |
| Portal backend calls | One `RestClient` with base URL `mitpass.portal.gateway-url` (`http://localhost:8080`), not load-balanced; an interceptor adds the session's bearer token and an `X-Correlation-Id`. Error bodies are read as `ProblemDetail`; field errors (`errors[]`) from sign-up are copied onto the form's `BindingResult` | D9: calls go through the gateway |

## 5. State machines

**Event** (`EventStatus`, transitions also in PROJECT.md §3):

| From | Allowed next | Who / what moves it |
|---|---|---|
| DRAFT | PUBLISHED | owner, admin |
| PUBLISHED | REGISTRATION_OPEN, CANCELLED | owner, admin; scheduler at `registrationOpensAt` |
| REGISTRATION_OPEN | REGISTRATION_CLOSED, CANCELLED | owner, admin; scheduler at `registrationClosesAt` (or `startsAt` if unset) |
| REGISTRATION_CLOSED | LIVE, CANCELLED | owner, admin; scheduler at `startsAt − doors-open-lead` |
| LIVE | COMPLETED | owner, admin; scheduler at `endsAt` |
| COMPLETED, CANCELLED | — | terminal |

Extra guards on top of the table: PUBLISHED needs at least the fields a student sees (already required at create); REGISTRATION_OPEN needs `startsAt` in the future. Each transition writes one `event_state_history` row and one `EventStateChanged` outbox row in the same transaction.

**Registration** (`RegistrationStatus`):

| From | Allowed next | When |
|---|---|---|
| PENDING | CONFIRMED, WAITLISTED | inside the registering transaction |
| WAITLISTED | CONFIRMED, CANCELLED | promotion; student or organizer cancels |
| CONFIRMED | CANCELLED | student or organizer cancels (only while the event is REGISTRATION_OPEN or REGISTRATION_CLOSED) |
| CANCELLED | — | terminal (a new registration is a new row) |

## 6. Data model (event-service)

Three new migrations, one per step that needs it, so each step can be verified alone. V1–V3 stay untouched.

**`V4__lifecycle_and_eligibility.sql`**

| Change | Details |
|---|---|
| `event` + `min_year smallint`, `max_year smallint` | null = no limit; CHECK `1..5` and `min_year <= max_year` |
| `event_eligible_department` | `event_id` FK `ON DELETE CASCADE`, `department_code varchar(16)` `^[A-Z]{2,16}$`; PK both. `@ElementCollection Set<String>`, LAZY, loaded by the detail `@EntityGraph` |
| `event_state_history` | `id` bigint identity, `event_id` FK cascade, `from_status` varchar(24) null, `to_status` varchar(24), `changed_at`, `changed_by` varchar(64), `trigger` varchar(16) `IN (MANUAL, SCHEDULER, SYSTEM)`, `reason` varchar(200) null; index `(event_id, changed_at)` |

**`V5__registrations.sql`**

| Change | Details |
|---|---|
| `event` + `seats_taken int NOT NULL DEFAULT 0` | CHECK `seats_taken >= 0 AND seats_taken <= capacity` (replaces nothing; `ck_event_capacity` stays) |
| sequence `registration_waitlist_seq` | FIFO order across all events (gaps are fine; only the order matters) |
| `registration` | `id` uuid PK (time-ordered), `event_id` FK, `user_id` uuid, `user_name` varchar(120), `user_email` varchar(254), `department` varchar(16) null, `year_of_study` smallint null, `status` varchar(16) `IN (PENDING, CONFIRMED, WAITLISTED, CANCELLED)`, `waitlist_seq` bigint null, `confirmed_at`, `waitlisted_at`, `cancelled_at` (all null), `cancelled_by` varchar(64) null, `via_promotion` bool default false, `version`, audit columns. CHECK `status <> 'WAITLISTED' OR waitlist_seq IS NOT NULL` |
| indexes | **partial unique** `uk_registration_active ON registration (event_id, user_id) WHERE status IN ('PENDING','CONFIRMED','WAITLISTED')`; `(event_id, status, waitlist_seq)`; `(user_id, created_at)` |
| `registration_history` | same shape as `event_state_history`, FK `registration_id` |

**`V6__idempotency_and_outbox.sql`**

| Table | Columns | Notes |
|---|---|---|
| `idempotency_record` | `user_id` uuid, `idem_key` varchar(80), `request_hash` varchar(64), `response_status` smallint, `response_body` text, `location` varchar(300) null, `created_at`, `expires_at`; PK `(user_id, idem_key)`; index `expires_at` | `VARCHAR`, not `CHAR` (phase-2 §16) |
| `outbox_message` | `id` uuid PK, `aggregate_type` varchar(40), `aggregate_id` varchar(64), `event_type` varchar(60), `payload` jsonb, `headers` jsonb, `occurred_at`, `published_at` null, `attempts` int default 0, `last_error` varchar(500) null; partial index `(occurred_at) WHERE published_at IS NULL` | DDL copied from `common-messaging`'s reference file; auth-service adds the same table in its `V3__outbox.sql` |

**Seed (`R__demo_seed.sql`)** gains: `AIWS26` limited to departments CSE, IT, AIDS (demo student Priya, CSE, is eligible; Arun, AERO, is not); `SPACETALK26` years 2–4; a new **`MINIQUIZ26` "Tech Quiz (demo: 1 seat)"**, capacity 1, REGISTRATION_OPEN, for the promotion demo with the two seeded students. The seed keeps `ON CONFLICT DO NOTHING`, so the eligibility rows and the new event are added by separate idempotent inserts. Seed dates are fixed (Nov 2026 – Jan 2027); if the demo runs after a close date, the scheduler closes that event (P8's seed generator fixes this for good).

## 7. REST API (event-service; same paths through the gateway)

| Method | Path | Who | Success | Errors |
|---|---|---|---|---|
| POST | `/api/events/{id}/transitions` `{to, reason?}` | owner ORGANIZER, ADMIN | 200 `EventDetailResponse` | 404, 409 `illegal-transition` (+ `allowedStates`), 409 stale (concurrent change) |
| GET | `/api/events/{id}/history` | owner ORGANIZER, ADMIN | 200 `[StateChangeResponse]` (oldest first) | 404 |
| GET | `/api/events/{id}/eligibility` | authenticated | 200 `{eligible, reasons[], seatsLeft, myRegistration?}` | 404 |
| POST | `/api/registrations` `{eventId}` + optional `Idempotency-Key` | STUDENT | 201 `RegistrationResponse {id, eventId, eventCode, eventTitle, status, waitlistPosition?, createdAt, …}` + `Location: /api/registrations/{id}` | 400 (bad key), 404 event, 409 `registration-closed`, 409 `already-registered`, 422 `not-eligible` (+ `reasons`), 422 `idempotency-key-mismatch` |
| GET | `/api/registrations/me?status=&page=` | authenticated | 200 `PageResponse<RegistrationResponse>` (with waitlist positions) | — |
| GET | `/api/registrations/{id}` | the student, event owner, ADMIN | 200 | 404 |
| POST | `/api/registrations/{id}/cancel` `{reason?}` | the student, event owner, ADMIN | 200 `RegistrationResponse` (CANCELLED; `promoted: {registrationId}` if someone moved up) | 404, 409 `illegal-transition` (already cancelled), 409 `registration-closed` (event LIVE or later) |
| GET | `/api/events/{id}/registrations?status=&page=` | owner ORGANIZER, ADMIN | 200 `PageResponse<RegistrationResponse>` | 404 |

Changed (fields only added, per AGENTS.md "published contracts only grow"): `EventCreateRequest` / `EventUpdateRequest` gain `eligibleDepartments`, `minYear`, `maxYear`. `EventSummaryResponse` gains `seatsTaken`, `seatsLeft`. `EventDetailResponse` gains those plus `eligibleDepartments`, `minYear`, `maxYear`, `waitlistCount`, `allowedTransitions`. `PUT /api/events/{id}` keeps its rules plus §4 "Edits by state". `DELETE` stays DRAFT-only.

**New problem types** (`urn:mitpass:problem:<slug>`): `illegal-transition` 409, `registration-closed` 409, `already-registered` 409, `not-eligible` 422, `idempotency-key-mismatch` 422. The partial unique index violation (`uk_registration_active`) is mapped to `already-registered` too, for the race between the pre-check and the insert.

## 8. The transactions, step by step

**Register** (`RegistrationService.register(user, eventId, key)`, one `@Transactional`):

1. If a key was sent: `IdempotencyStore.claim(userId, key, hash)`. Already there → return the stored response (or 422 on a different hash). The rest is skipped.
2. Load the event (404) and run `EligibilityPolicy` (409 / 422).
3. Pre-check for an active registration (409 `already-registered`; the partial unique index backs it up).
4. Insert the registration as PENDING (+ history).
5. Fast path: conditional `UPDATE … seats_taken + 1`. 1 row → CONFIRMED.
6. Slow path: lock the event row, re-check, then CONFIRMED (claim) or WAITLISTED (`nextval`).
7. Outbox: `RegistrationConfirmed` (CONFIRMED only; waitlisting publishes nothing in P3).
8. Store the idempotent response; commit.

**Cancel** (`RegistrationService.cancel(id, actor, reason)`, one `@Transactional`): lock the event row → state machine check → CANCELLED (+ history, `RegistrationCancelled`). If it was CONFIRMED: next WAITLISTED row `FOR UPDATE` → CONFIRMED with `via_promotion` (+ history, `WaitlistPromoted`, `RegistrationConfirmed`), else `seats_taken - 1`. Commit.

**Transition** (`EventLifecycleService.transition(id, to, actor, trigger, reason)`): load with `@Version` → `LifecycleState.require` → extra guards → set status → history + `EventStateChanged` → commit. A concurrent edit makes the flush fail with an optimistic-lock error → 409 (manual) or skip (scheduler).

## 9. `libs/common-messaging`

Auto-configuration (registered in `AutoConfiguration.imports`):

- **`OutboxPublisher`** (`@ConditionalOnBean(DataSource)`): `publish(aggregateType, aggregateId, Object event)`. The event type comes from the record's class (`@EventType("registration.confirmed")` annotation on each contract record). Serializes with the app's Jackson 3 `JsonMapper`, adds `correlationId` and `userId` from the MDC, inserts with `JdbcClient`. Propagation MANDATORY.
- **`OutboxRelay`** (`@ConditionalOnProperty mitpass.messaging.relay.enabled`, default true; `@ConditionalOnBean(RabbitTemplate)`): the polling loop in §4. Publishes raw JSON bytes with `MessageProperties` (no message converter involved), waits for confirms (`publisher-confirm-type: simple`, `waitForConfirmsOrDie`). One INFO line per batch, WARN on failure.
- **`MessagingTopology`**: declares `TopicExchange mitpass.events`; the tap queue + binding when `mitpass.messaging.tap-queue.enabled`.
- **`OutboxCleanup`**: deletes published rows older than `retention` on the relay's scheduler (hourly).
- **`contract` package**: the five records of §4, an `EventEnvelope` record, and `EventTypes` constants.
- **`src/main/resources/mitpass/messaging/outbox-postgresql.sql`**: the reference DDL that services copy into a migration; the lib's own test runs it against Testcontainers PostgreSQL so it can't rot.

P4 adds the consumer side (`processed_message`, `IdempotentConsumer`) to this module.

## 10. Portal slice (`web-portal` :8090)

D9 rules kept from the start: tokens only in the server-side session, CSRF on, PRG on every form post, all calls through the gateway. Everything else is plain on purpose.

| Page | Path | Who | Backend calls |
|---|---|---|---|
| Sign up | `GET/POST /signup` | anonymous | `GET /api/departments`, `POST /api/auth/signup`; 400 field errors → `BindingResult`; success → redirect `/login?signedUp` (flash: "check your email", Mailpit link in dev) |
| Log in / out | `GET /login`, `POST /login`, `POST /logout` | — | `POST /api/auth/login`, `POST /api/auth/logout` |
| Catalogue | `GET /events?q=&category=&page=` | anyone | `GET /api/events`, `GET /api/categories` |
| Event detail | `GET /events/{id}` | anyone (eligibility and "my status" only when logged in) | `GET /api/events/{id}`, `GET /api/events/{id}/eligibility` |
| Register | `POST /events/{id}/register` (hidden `idempotencyKey`) | STUDENT | `POST /api/registrations` → redirect `/events/{id}` with flash "CONFIRMED" or "WAITLISTED #n", or the problem's `detail` |
| My registrations | `GET /my/registrations`, `POST /my/registrations/{id}/cancel` | logged in | `GET /api/registrations/me`, `POST /api/registrations/{id}/cancel` |
| My events (organizer) | `GET /organizer/events`, `POST /organizer/events/{id}/transitions` | ORGANIZER, ADMIN | `GET /api/events?organizerId=me`, `GET /api/events/{id}` (for `allowedTransitions` and counts), `POST /api/events/{id}/transitions` |

- Layout: one Thymeleaf layout fragment (header with the user's name and role-dependent links via `sec:authorize`, flash area). Minimal CSS in one static file; the maroon header colour from the UI reference is the only nod to the design.
- The event page follows the mockup's screen 2 content: eligibility line ("CSE, IT, AIDS · any year"), seats left (`588 / 600`), registration close time, "You're eligible: CSE · 1st year" or the reasons, a Register button. Deviation from the mockup: its note "after closing you can join the waitlist" is wrong for this plan (the waitlist is for a full event while registration is open); the page says "Full: you'll join the waitlist" instead.
- `PortalAccessTest` plays the security-matrix role for the portal: anonymous → redirect to `/login` on `/my/**` and `/organizer/**`; STUDENT → 403 on `/organizer/**`; POST without CSRF → 403.
- D9 shortcuts used here and already listed in D9: re-login on expiry, minimal styling and full reloads, verification link opens the API, Spring's default error page. **New shortcut to add to D9** at T05.12 if it survives the build: "the waitlist position updates on reload" (P6: HTMX refresh; P7: push).

## 11. Gateway and other services

- event-service route predicate gains `/api/registrations/**`. No new public paths (eligibility and registrations need a token). `EdgeSecurityTest` gets one case: `POST /api/registrations` without a token → 401 at the edge.
- auth-service: `V3__outbox.sql`; `SignupService` publishes `UserRegistered {userId, email, fullName, department, year, registeredAt}` through `OutboxPublisher` in the sign-up transaction. Verification mail stays as built in P2.
- CORS unchanged (the portal calls the gateway server to server, so no browser preflights).

## 12. Configuration additions

```yaml
# event-service
mitpass:
  events:
    scheduler: { enabled: true, interval: 30s, doors-open-lead: 30m }
  idempotency: { ttl: 24h, cleanup-interval: 1h }
  messaging:
    exchange: mitpass.events
    relay: { enabled: true, poll-interval: 500ms, batch-size: 100, retention: 7d }
    tap-queue: { enabled: true, name: mitpass.events.tap, max-length: 1000 }
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    username: ${RABBITMQ_USER:mitpass}
    password: ${RABBITMQ_PASSWORD:mitpass}
    publisher-confirm-type: simple

# auth-service: the same mitpass.messaging + spring.rabbitmq blocks

# web-portal
server.port: 8090
mitpass.portal:
  gateway-url: ${GATEWAY_URL:http://localhost:8080}
  mailpit-url: http://localhost:8025     # shown after sign-up in dev only
server.servlet.session: { timeout: 30m, cookie: { http-only: true, same-site: lax } }
```

Tests (`application-test.yml`): `mitpass.events.scheduler.enabled: false`, `mitpass.messaging.relay.enabled: false`, `tap-queue.enabled: false`, and RabbitMQ auto-configuration left on but never connected (no relay → no connection). Only `OutboxRelayIT` and `MessagingFlowTest` start a RabbitMQ container.

## 13. Ports and running locally

| Port | What |
|---|---|
| 8090 | **web-portal** (new) |
| 15672 | RabbitMQ management UI (`mitpass` / `mitpass`), already in compose |
| 8080 / 8081 / 8082 / 8761 / 8025 | gateway / auth / event / Eureka / Mailpit (unchanged) |

Start order: discovery → auth → event → gateway → portal. Port 8080 may be taken by the owner's unrelated Node server (log.md notes); the portal then can't reach the gateway either.

## 14. Tests

| Test | Kind | Proves |
|---|---|---|
| `LifecycleStateTest` (common-web), `EventStatusTest`, `RegistrationStatusTest` | unit | every allowed move passes; every other pair throws with the right `allowedStates`; terminal states allow nothing |
| `IllegalTransitionHandlerTest` (common-web) | `@WebMvcTest` probe | 409 ProblemDetail with `currentState`, `requestedState`, `allowedStates`, `correlationId` |
| `EligibilityPolicyTest` | unit, fixed `Clock` | department / year / missing year / window / state, several reasons at once |
| `EventLifecycleServiceTest` | `@DataJpaTest` + Testcontainers | transition writes history + outbox row; illegal → exception and nothing written; stale version → optimistic lock; edits-by-state rules; capacity below `seats_taken` → 422; capacity raise promotes |
| `EventLifecycleSchedulerTest` | `@SpringBootTest` + Testcontainers, fixed `Clock` | each of the four time-based moves happens once; an event changed by hand meanwhile is skipped |
| `RegistrationServiceTest` | `@SpringBootTest` + Testcontainers | confirm, waitlist, cancel CONFIRMED with and without waiters, cancel WAITLISTED, positions after cancellations, already-registered (pre-check and constraint), re-register after cancel, closed / LIVE event, outbox rows per case |
| **`SeatClaimConcurrencyTest`** | `@SpringBootTest` + Testcontainers | 500 distinct students, 100 seats, a start latch and a 32-thread pool (Hikari pool 20 in the test): exactly 100 CONFIRMED, 400 WAITLISTED, positions 1..400, `seats_taken == 100`, 100 outbox rows. **Naive contrast** (`NaiveRegistrar`, test sources only: "count confirmed, compare, insert", with a small pause between read and write to make the race visible) overbooks; the test prints both rows of the table. **Cancel/promote race**: 50 cancellations and 50 new registrations at once on a full event → invariant holds |
| `IdempotencyStoreTest` | `@DataJpaTest` + Testcontainers | replay returns the stored response; mismatch → 422; two threads with one key → one registration, one replay; rolled-back request leaves no key; expired keys cleaned up |
| `RegistrationControllerTest`, `LifecycleControllerTest` | `@WebMvcTest` | status codes, problem types, `Location`, `Idempotent-Replayed`, key validation |
| `OutboxPublisherTest` (common-messaging) | Testcontainers PostgreSQL | row written inside a transaction, nothing after a rollback, MANDATORY outside a transaction throws; reference DDL runs |
| `OutboxRelayIT` (common-messaging) | Testcontainers PostgreSQL + RabbitMQ | rows published once with properties and envelope, marked published; broker down → `attempts` grows, then succeeds; two relays → no duplicates |
| `MessagingFlowTest` (event-service) | Testcontainers PostgreSQL + RabbitMQ | cancel with a waiter → the three messages arrive on a test queue in order, with the correlation id |
| `SecurityMatrixTest` (event-service, extended) | existing harness | every §7 row × every role |
| `SignupOutboxTest` (auth-service) | existing `@AuthIntegrationTest` | sign-up writes a `UserRegistered` outbox row; a failed sign-up writes none |
| Portal: `SignupControllerTest`, `EventPagesControllerTest`, `RegistrationPagesTest`, `OrganizerPagesTest` | `@WebMvcTest` + mocked API clients | form validation and backend field errors on the form, PRG redirects and flash messages, only allowed transition buttons rendered, hidden idempotency key |
| Portal: `GatewayClientTest` | `MockRestServiceServer` | bearer token and correlation id sent; ProblemDetail mapped; 401 → session expired |
| Portal: `PortalAccessTest`, `TokenNeverInPageTest` | `@SpringBootTest(MOCK)` | §10 access rules; rendered HTML and cookies never contain the access or refresh token; `JSESSIONID` is HttpOnly |

## 15. Steps (log.md T05.1–T05.12)

Each step ends with `./mvnw -pl <module> -am verify` green, a full `./mvnw verify` before the next step that touches a lib, and a `log.md` entry.

| # | Step | Size | Done when |
|---|---|---|---|
| T05.1 | **common-web:** `LifecycleState`, `IllegalTransitionException`, its 409 handler, new problem-type slugs | S | `LifecycleStateTest`, `IllegalTransitionHandlerTest` green; full suite green |
| T05.2 | **Event state machine:** transition table, V4 (history, eligibility columns), `EventLifecycleService`, transitions + history endpoints, edits-by-state rules, `allowedTransitions` / eligibility fields in DTOs, seed eligibility | M | `EventStatusTest`, `EventLifecycleServiceTest` (minus outbox), controller tests green; existing event tests still green |
| T05.3 | **Scheduler:** `EventLifecycleScheduler` with `Clock`, config, off in tests | S | `EventLifecycleSchedulerTest` green |
| T05.4 | **`libs/common-messaging`:** publisher, relay (SmartLifecycle, confirms, SKIP LOCKED), topology, tap queue, cleanup, envelope + contracts, reference DDL, auto-config | M | `OutboxPublisherTest`, `OutboxRelayIT` green |
| T05.5 | **Registrations:** V5, `Registration` + state machine, `EligibilityPolicy`, seat claim (fast + slow path), cancel + promote, capacity-raise promotion, eligibility endpoint, registration endpoints, `RegistrationSecurity`, MINIQUIZ26 seed | L | `EligibilityPolicyTest`, `RegistrationServiceTest`, controller tests green |
| T05.6 | **Idempotency-Key:** V6 (idempotency part), `IdempotencyStore`, header handling, replay header, cleanup | M | `IdempotencyStoreTest` green incl. the two-thread case |
| T05.7 | **Outbox wiring:** V6 outbox table, events from lifecycle / register / cancel / promote, RabbitMQ config; auth-service `V3__outbox.sql` + `UserRegistered` | M | `MessagingFlowTest`, `SignupOutboxTest` green; live: messages visible in the tap queue |
| T05.8 | **Concurrency tests:** 500/100, naive contrast, cancel/promote race | M | `SeatClaimConcurrencyTest` green three runs in a row (flakiness check), output table readable |
| T05.9 | **Gateway + security matrix:** `/api/registrations/**` route, `EdgeSecurityTest` case, matrix rows, `docs/security-matrix.md` regenerated | S | every matrix cell green |
| T05.10 | **web-portal skeleton + auth:** module, layout, Spring Security form login against auth-service, session tokens, expiry filter, logout, sign-up page | M | `GatewayClientTest`, `SignupControllerTest`, `PortalAccessTest`, `TokenNeverInPageTest` green; live sign-up → Mailpit → login works |
| T05.11 | **web-portal pages:** catalogue, event detail (seats, eligibility), register/cancel with idempotency key, my registrations, organizer my events with transition buttons | M | page tests green; live portal journey of exit criterion 8 works |
| T05.12 | **Exit demo + docs:** `docs/http/phase-3.http`; README (portal, RabbitMQ UI, start order, demo journey); PROJECT.md §P3 ticks + status, §3 table if events changed; DECISIONS (new D10 messaging/outbox conventions, D11 state machines + locking, D9 row if added); "As built" here; log Current Status | S | all ten exit criteria pass; nothing left running |

Order: T05.1 → T05.2 → T05.3 → T05.4 → T05.5 → T05.6 → T05.7 → T05.8 → T05.9 → T05.10 → T05.11 → T05.12. T05.4 can run in parallel with T05.2–T05.3, and T05.10 with T05.5–T05.9 (the portal needs only the P2 auth API for its first step).

## 16. Gotchas to check at build time

1. **Read-only `seats_taken`:** a `@Modifying` JPQL/SQL update bypasses the persistence context. Use `clearAutomatically = true` or re-read the counter, or a loaded `Event` shows a stale value. The update doesn't bump `@Version` (intended).
2. **`@Lock(PESSIMISTIC_WRITE)` on PostgreSQL:** Hibernate 7 may emit `FOR NO KEY UPDATE` instead of `FOR UPDATE`. Either works here (registrations reference the event by FK, which `FOR NO KEY UPDATE` still allows); confirm in the SQL log.
3. **Constraint name from a partial unique index:** map `DataIntegrityViolationException` by constraint name (`uk_registration_active`) to `already-registered`; common-web's generic handler would say "conflict" without the slug.
4. **`INSERT … ON CONFLICT DO NOTHING`** must be a native `@Modifying` query (or `JdbcClient`); it returns the row count, which decides claim vs replay.
5. **`JdbcClient` inside a JPA transaction:** works because `JpaTransactionManager` binds the JDBC connection. Verify with the rollback test before relying on it.
6. **Thread pool vs connection pool** in the concurrency test: 500 threads on 10 connections leads to `Connection is not available` timeouts, not to a correctness failure. Use a bounded executor and set `maximum-pool-size` in the test profile.
7. **Testcontainers 2 package names:** the RabbitMQ container class may have moved to `org.testcontainers.rabbitmq` (the PostgreSQL one moved to `org.testcontainers.postgresql`). `@ServiceConnection` supports RabbitMQ.
8. **Spring AMQP 4 / Jackson 3:** the relay publishes bytes, so no message converter is needed in P3. P4's consumers will need the Jackson 3 converter; check its class name then.
9. **Publisher confirms:** `publisher-confirm-type: simple` + `RabbitTemplate.invoke(ops -> { …; ops.waitForConfirmsOrDie(timeout); })`. Without confirms, "published" may mean "lost in the socket buffer".
10. **Load-balanced beans:** if the portal or common-messaging customises a `RestClient.Builder` or `RabbitTemplate`, use `@Bean(defaultCandidate = false)` where a framework bean of the same type is expected (phase-2 §16 lesson).
11. **Thymeleaf + CSRF:** only `th:action` forms get the hidden `_csrf` field automatically.
12. **Spring Security login errors:** the exception from the custom `AuthenticationProvider` ends up in the `SPRING_SECURITY_LAST_EXCEPTION` session attribute; the login page reads its message for "verify your email" / "locked until".
13. **`ProblemDetail` with Jackson 3** in the portal: extension members (`errors`, `reasons`, `allowedStates`) land in `getProperties()`. Check deserialization with a real body in `GatewayClientTest`.
14. **Repeatable seed + new NOT NULL columns:** V5's `seats_taken` has a default, so `R__demo_seed.sql` keeps working; the new seed rows must still be idempotent inserts.
15. **Scheduler and tests:** `@Scheduled` beans must be conditional on `mitpass.events.scheduler.enabled`, or a test context closes events under the tests' feet.

## 17. Owner questions

Defaults are in §2; the plan above already uses them.

1. **Re-registration after cancelling:** allow it (one *active* registration per student per event, partial unique index), or keep PROJECT.md's literal `unique (event_id, user_id)` so a cancellation is final?
2. **Scheduler scope:** should the scheduler also move events to LIVE (30 min before the start) and COMPLETED (at the end), or only open and close registration, leaving LIVE / COMPLETED to the organizer's buttons?
3. **`UserRegistered` from auth-service in P3:** include it (small, deferred here by the phase-2 plan), or leave it for P7 when notification-service consumes it?
4. **Pre-phase commit:** the working tree is clean on `phase3` apart from this plan and `log.md`. Commit them as `chore: plan Phase 3` before T05.1?

# Decisions

Architecture and tooling decisions, newest last. Each entry says what was chosen, why, and what it costs. Phase plans in `docs/plans/` hold their own detailed choices. A choice is promoted here when it affects more than one phase.

---

## D1: Spring Boot 4.1.1 + Spring Cloud 2025.1.3 (Oakwood), Java 21

*2026-10-09 · accepted*

**Decision.** The root `pom.xml` inherits `spring-boot-starter-parent` **4.1.1** and imports `spring-cloud-dependencies` **2025.1.3**. Java stays at **21**, as `PROJECT.md` planned.

**Why.**
- Spring Cloud 2025.1.x is the only supported release train. 2025.0 (Boot 3.5) is end of life on spring.io. 2025.1.x supports Boot 4.0.x and, from 2025.1.2, Boot 4.1.x.
- Boot 4.1.1 (2026-08-20) is the newest GA. 4.2 is still at milestones, and there is no matching GA Cloud train yet (2026.0.0-M1).
- springdoc-openapi 3.1.x is the line built for Boot 4.1 (3.1.0's notes say "Upgrade Spring Boot to version 4.1.0").

**Versions managed by the BOMs** (do not override without a reason logged here):

| Library | Version | | Library | Version |
|---|---|---|---|---|
| Spring Framework | 7.0.9 | | Testcontainers | 2.0.5 |
| Hibernate ORM | 7.4.5.Final | | JUnit Jupiter | 6.0.3 |
| Hibernate Validator | 9.1.3.Final | | Mockito | 5.23.0 |
| Spring Data | 2026.0.1 (JPA 4.1.1) | | Jackson | 3.1.5 (Jackson 2 BOM 2.21.5) |
| Flyway | 12.4.0 | | Spring AMQP | 4.1.1 |
| PostgreSQL JDBC | 42.7.13 | | Spring Cloud Gateway / Netflix | 5.0.3 / 5.0.2 |

**Pinned in the root POM:** springdoc-openapi 3.1.1, MapStruct 1.6.3, ZXing 3.5.4 (`core`; `javase` only in tests) and OpenPDF 3.0.5 (Phase 4; neither is in the Boot BOM; latest releases on Maven Central on 2026-10-16). OpenPDF 3 uses the `org.openpdf.text` packages; its `brotli4j` dependency (a per-OS native jar) is excluded because tickets don't need it.

**Pinned in the root POM (Phase 6):** the WebJars `org.webjars.npm:htmx.org` **2.0.11** and `org.webjars.npm:html5-qrcode` **2.3.8** (the last release, 2023; D15). `webjars-locator-lite` comes from the Boot BOM, so templates use version-free `/webjars/htmx.org/dist/htmx.min.js` paths. The UI checks' Playwright (1.63.0) is an npm dev dependency in `web-portal/ui-checks/package.json`, not part of the Maven build.

**Consequences.** Boot 4 differs from most tutorials (which target Boot 3). The practical differences are listed in `docs/plans/phase-1.md` ("Boot 4 gotchas"). Patch upgrades within 4.1.x are free. Moving to 4.2 waits for a Cloud train that supports it.

---

## D2: The toolchain stays project-local

*2026-10-09 · accepted (AGENTS.md "Dependencies (strict)")*

**Decision.**
- **JDK:** Eclipse Temurin `jdk-21.0.12.1+1`, pinned with its SHA-256 in `scripts/setup-jdk.ps1` and unpacked into `.tools/jdk-21/` (gitignored). Nothing outside the repo changes.
- **Maven:** Maven Wrapper 3.3.4 (`only-script`) running Maven **3.9.16**, pinned by SHA-256 in `.mvn/wrapper/maven-wrapper.properties`. Maven itself lands in `~/.m2/wrapper/`, which AGENTS.md allows.
- **Wrapper patch:** `mvnw` and `mvnw.cmd` each carry a small block marked `MITPass:`. It sets `JAVA_HOME` to `.tools/jdk-21` when `JAVA_HOME` is unset, because the machine's `java` on PATH is Java 8. **Reapply it after any wrapper upgrade.**
- **Guard:** `maven-enforcer-plugin` requires Java 21+ and Maven 3.9+, so a wrong JDK fails with a message that names the fix.
- **IDE:** `.vscode/settings.json` points VS Code terminals at the same JDK.
- **CI:** GitHub Actions uses `actions/setup-java` (Temurin 21) instead of the script.

**Consequences.** A fresh clone on Windows needs one command (`scripts/setup-jdk.ps1`) before `./mvnw` works. On Linux or macOS, any JDK 21 on `JAVA_HOME` works.

---

## D3: Docker Desktop installed globally (owner-approved)

*2026-10-09 · accepted*

**Decision.** Docker Desktop **4.94.0** was installed with `winget install Docker.DockerDesktop` (engine 29.8.2, Compose v5.5.1, WSL2 backend). The owner approved it explicitly on 2026-10-09, as AGENTS.md requires for a global install.

**Why.** A container runtime cannot be project-local. `docker compose` (PostgreSQL, RabbitMQ, Mailpit) and Testcontainers both need it from Phase 1 on.

**Consequences.** Docker Desktop must be running before `docker compose up` or any Testcontainers test. Terminals opened before the install don't have `docker` on PATH; open a new one.

---

## D4: Coordinates and module layout

*2026-10-09 · accepted (Phase 1 plan approved with these defaults; code now uses them)*

**Decision.** groupId `com.mitpass`, root artifact `mitpass-parent`, version `0.1.0-SNAPSHOT`, Java packages `com.mitpass.<module>` (for example `com.mitpass.event`). The root POM lists modules by path (`libs/common-web`, `services/event-service`, …), and every module's parent is the root POM. There are no intermediate aggregator POMs.

---

## D5: Line endings

*2026-10-09 · accepted*

**Decision.** `.gitattributes` keeps LF in the repo and the working tree. `.cmd`, `.bat` and `.ps1` use CRLF, and `mvnw` is always LF. `MITPass App Screens.html` is marked `-diff`, because it is a gzip+base64 bundle and line diffs of it are noise.

**Watch out.** Git on Windows doesn't record the executable bit (`core.filemode=false`), so `mvnw` must get it in the index, or CI on Linux fails with "Permission denied". Done on 2026-10-09 with `git add --chmod=+x mvnw` (`git update-index --chmod=+x` only works on files already tracked). Later `git add` runs keep the bit. Check with `git ls-files -s mvnw`, which should show `100755`.

---

## D6: REST conventions every backend service follows

*2026-10-10 · accepted (built in Phase 1; details in `docs/plans/phase-1.md` §4, §6, §14)*

**Decision.**
- **Paths.** A service serves its resources under `/api/<resource>` itself. The gateway routes by path prefix **without rewriting**, so Swagger "Try it out" and `Location` headers work through `:8080` unchanged.
- **API docs at the gateway.** Each service's OpenAPI document is proxied at **`/docs/<service-name>`** (`SetPath=/v3/api-docs`) and listed in `springdoc.swagger-ui.urls`. It is not under `/v3/api-docs/**`, because springdoc's own handler in the gateway would answer first.
- **Errors.** A service depends on `libs/common-web`, which auto-configures the `GlobalExceptionHandler`. Every error is `application/problem+json` with `type` = `urn:mitpass:problem:<slug>`, `title`, `status`, `detail`, `instance`, `correlationId`, plus `errors[]` (`field`, `message`) for validation. The status map is 400 validation/malformed, 404 not found, 409 conflict (duplicates, wrong state, stale version), 422 business rule needing the database, and 500 generic with no internals leaked.
- **Correlation ids.** The gateway stamps `X-Correlation-Id` (keeping a safe client value). Services put it in the MDC, the log pattern (`%5p [%X{correlationId:-}]`) and every ProblemDetail.
- **Paging.** List endpoints return `PageResponse<T>` (`content, page, size, totalElements, totalPages`), never Spring's `PageImpl`. `max-page-size: 100`.
- **Exceptions to throw.** `NotFoundException`, `ConflictException`, `BusinessRuleException` from `common-web`.

**Consequences.** Adding a service (Phase 2 onward) means adding one `common-web` dependency, one route pair in the gateway and one `swagger-ui.urls` entry. A Phase 2 caveat: `GlobalExceptionHandler` has a catch-all `Exception` → 500 handler, so method-security `AccessDeniedException`s must be mapped to 403 explicitly when Spring Security arrives. *(Resolved in Phase 2: common-security's `SecurityExceptionAdvice` is ordered first; see D7.)* Phase 2 also added `ProblemException` (any status, type and headers) and a public `Problems` factory for filter-level errors.

---

## D7: Security model (stateless JWTs, verified everywhere)

*2026-10-11 · accepted (built in Phase 2; details in `docs/plans/phase-2.md` §4, §8, §9, "As built")*

**Decision.**
- **Issuer.** `auth-service` mints **RS256** access tokens itself with Spring Security's `NimbusJwtEncoder` (not Spring Authorization Server). Claims: `iss=mitpass-auth`, `aud=mitpass-api`, `sub` (user UUID), `email`, `name`, `roles`, `dept`, `year`, `regNo`, `iat`, `exp` (15 min), `jti`; header `kid`.
- **Keys.** RSA-2048 in `auth_db.signing_key`, private half encrypted at rest (AES-256-GCM, key from `MITPASS_KEY_SECRET` via PBKDF2). At most one ACTIVE key (partial unique index). `POST /api/auth/keys/rotate` (ADMIN); a retired key stays in `/.well-known/jwks.json` for one token lifetime + 5 min.
- **Verification.** Every servlet service depends on `libs/common-security`, which auto-configures a stateless resource server: JWK set from `http://auth-service/.well-known/jwks.json` through Eureka (`@LoadBalanced` client), RS256 only, `iss`/`aud`/`exp` (30 s skew)/`sub` validators, `roles` → `ROLE_*`. The gateway does the same reactively as a **pre-check** (valid token or public path) and forwards the token unchanged; services verify again and own every role rule.
- **Authorization.** URL rules only split public from authenticated (`mitpass.security.public-paths`). Role and ownership rules are `@PreAuthorize` on controller methods (`@eventSecurity.isOwner(#id)` for organizer ownership).
- **Implied role (owner, RL11, 2026-10-16).** ORGANIZER implies VOLUNTEER: auth-service adds VOLUNTEER whenever an admin sets ORGANIZER (`RoleName.withImplied`), and migration V4 gave it to existing organizers. At the gate (checkin-service) an organizer may scan at any gate of an event they organize without an assignment; anywhere else they need an assignment like any volunteer. Revoking passes stays with the event's organizer and admins.
- **Errors.** 401/403 are ProblemDetails with types `unauthorized` / `forbidden` (filter level: entry point + access-denied handler; method level: `SecurityExceptionAdvice`). Business auth errors have their own types (`invalid-credentials`, `email-not-verified`, `account-locked` 423, `refresh-token-reused`, …).
- **Sessions.** Refresh tokens are opaque, stored as SHA-256, rotated on every use within a family; reuse revokes the family. Logout is **stateless** (owner decision): it revokes the refresh family, and an issued access token lives out its ≤ 15 min. No denylist until Redis (P8 at the earliest).
- **Service-to-service.** Calls carry the original caller's token (`BearerTokenRelayInterceptor`), so the callee authorizes the user, not the calling service. **Exception (P5, D14):** background jobs that have no caller use a short-lived **service token** (`POST /api/auth/service-token`, client id + secret from config, `roles = [SERVICE]`, 5 min, refused at the gateway so it is internal only). `SERVICE` is not a user role (not in `Roles.ALL`); an endpoint allows it explicitly.

**Consequences.** A new service gets authentication by adding `common-security`, listing its public paths, and putting `@PreAuthorize` on writes. Tests use `libs/security-test-support` (`TestJwtIssuer` + `TestSecurityConfig`), which trust an in-memory key but keep the production validators. Each service has a `SecurityMatrixTest` whose table is in `docs/security-matrix.md`; a new endpoint belongs in it.

---

## D8: Demo accounts and dev secrets

*2026-10-11 · accepted*

**Decision.** `auth-service` seeds six accounts through a repeatable `db/seed/R__demo_users.sql` (off in tests) with fixed UUIDs `00000000-0000-4000-8000-00000000000N` and the password `Passw0rd!`. Staff addresses use the reserved `.test` TLD so they can't belong to real people. event-service's seed and its V3 migration give the demo events to organizer A (`…02`) and organizer B (`…03`) by those ids. The signing-key secret defaults to `dev-only-change-me` (`MITPASS_KEY_SECRET` overrides it).

**Consequences.** Anything beyond a local demo must override `MITPASS_KEY_SECRET` and not load `db/seed`. Changing the seeded organizer ids means changing both services' seeds together.

---

## D9: Thin portal slices in P3–P5, finished in P6

*2026-10-15 · accepted (owner request; PROJECT.md §5 "Portal slices")*

**Decision.** P3, P4 and P5 each add a thin `web-portal` page set so the owner can see that phase's backend working in a browser. The slices are deliberately rough. P6 turns them into the full portal. The owner wanted early visibility without pulling P6's work forward.

**Slices get these right from the start**, because they are hard to retrofit:
- Tokens stay in the server-side session and never reach the browser (BFF, PROJECT.md §3).
- Spring Security's CSRF protection stays on.
- Form posts use Post/Redirect/Get.
- Calls go through the gateway.

**Everything else is a known shortcut.** Don't fix one early, and don't treat one as a bug:

| Slice shortcut | From | P6 replaces it with | Replaced in P6 by |
|---|---|---|---|
| User logs in again when the 15 min access token expires | P3 | Automatic refresh with the refresh token held in the session | `TokenRefresher` refreshes 60 s ahead (at most a quarter of the token's lifetime) under a per-session lock; one refresh + retry on a 401 (T08.3) |
| Minimal styling, plain search form, full page reloads | P3 | UI reference visual language (hand-written CSS with design tokens, D15), HTMX live search and filters, dark mode, responsive layout | `tokens.css` + `portal.css` (D15), HTMX live search with filters and `hx-push-url`, dark mode, 360 px layouts (T08.2, T08.4) |
| Email verification link opens the auth-service API (`mitpass.auth.verify-link`) | P3 | Link to a portal page | `/verify-email` portal page (button, POST spends the token); auth-service `verify-link` default points there (T08.3) |
| Spring's default error page | P3 | Custom 403/404/500 pages, i18n message bundles | `error/403`, `404`, `500`, `503` (+ 4xx/5xx) with the correlation id; English + Tamil bundles (T08.2, T08.11) |
| Live QR is an `<img>` reloaded by a plain 30 s timer | P4 | Pass card with a countdown ring (screens 3–4) | Pass card: SVG code, preload-then-swap, countdown ring, `data-refresh-ms` (no JS inlining) (T08.5) |
| Scan page takes a typed pass number or pasted token; plain result page | P5 | `html5-qrcode` camera scanner over HTTPS (self-signed keytool cert, D15), manual-entry tab, green/red flash, sound, vibration | Camera scanner (`html5-qrcode`), `/scan/submit` + `HX-Trigger` flash/tone/vibration, manual tab, `lan` HTTPS profile (T08.8, T08.9) |
| Attendance page updates on reload | P5 | Styled page in P6; live WebSocket updates in P7 | Styled Reports tab (screen 7) with an HTMX Refresh; live push stays P7 (T08.7) |
| Registration status and waitlist position update on reload (*My registrations*, event page) | P3 | HTMX refresh in P6; push updates with the P7 notifications | HTMX refresh of *My registrations* and the event's registration box while waiting, stopped with 286 (T08.4) |
| Organizer "My events" loads each event's detail for its buttons (one call per event) | P3 | A list endpoint that carries `allowedTransitions`, or HTMX per row | `allowedTransitions` on event-service's list; one call (T08.4/T08.6) |
| A new pass appears in *My passes* on reload (about a second after registering); the event page only says it is being issued | P4 | HTMX poll on the event page in P6; push with the P7 notifications | "You're in" (screen 3) polls for the pass and stops with 286 (T08.4) |
| The QR image and PDF are passed through the portal byte for byte (`/my/passes/{id}/qr.png`, `ticket.pdf`) | P4 | Kept in P6 (it is the BFF rule); P6 adds the countdown ring around it | Kept (BFF rule); the pass card wraps it (T08.5) |
| Lifecycle buttons as actions and confirmation pages for critical actions are plain server pages (`LifecycleActions`, `confirm-transition.html`, `confirm-revoke.html`; owner RL8/RL9, done early) | P5 | Wording moves into the i18n bundles; the confirmation can become an HTMX dialog, still enforced by the server | Labels/consequences in the bundles (`action.*`, `consequence.*`); dialogs with `confirmed=true`, still enforced by the server (T08.6) |

**Consequences.** If a slice can't work without a P6 item, build the smallest version that works and add a row to this table. P6 is done when every row above is replaced. **All rows were replaced in Phase 6 (2026-10-18), last column; checked in the browser with `web-portal/ui-checks`.**

---

## D10: State machines, seat claims and registrations

*2026-10-15 · accepted (owner answers to `docs/plans/phase-3.md` §17; details in the plan §4–§8)*

**Decision.**
- **One transition method per aggregate.** Each status enum implements `LifecycleState` (common-web) with an `EnumMap` table of allowed next states. An illegal move throws `IllegalTransitionException` → `409 illegal-transition` with `currentState`, `requestedState`, `allowedStates`. Every move writes a history row (`trigger` MANUAL / SCHEDULER / SYSTEM). pass-service (P4) and checkin-service (P5) use the same helper.
- **Event lifecycle (owner).** The scheduler opens and closes registration, makes the event **LIVE (scheduler only, no button)** at `startsAt − doors-open-lead`, and completes it at `endsAt`. Organizers can open/close registration early, complete, cancel, and **undo LIVE → REGISTRATION_CLOSED** after an accidental early start. Open points: `docs/REVIEW-LATER.md` RL2, RL3.
- **Registration window.** The status is the gate: registering needs REGISTRATION_OPEN and `now < registrationClosesAt`.
- **One active registration per student per event (owner).** Partial unique index on `(event_id, user_id)` for PENDING / CONFIRMED / WAITLISTED. Re-registering after a cancellation creates a new row.
- **Seat claim.** `seats_taken` is read-only in JPA and changed only by queries. Fast path: conditional `UPDATE … WHERE seats_taken < capacity`. Full: `PESSIMISTIC_WRITE` on the event row, re-check, then claim or waitlist. Cancellation locks the event row and promotes the first waitlisted student in the same transaction. A CHECK keeps `seats_taken` within `0..capacity`. READ COMMITTED isolation throughout.
- **Idempotency-Key** on `POST /api/registrations`: stored per user in the same transaction as the registration (`INSERT … ON CONFLICT DO NOTHING`), replayed with `Idempotent-Replayed: true`, 422 on a different body. Lives in event-service until a second service needs it.

**Consequences.** Any new stateful aggregate gets an enum table and a history table, not ad-hoc `if`s. Code that changes seats must go through the repository queries, never through a setter.

---

## D11: Domain events through a transactional outbox

*2026-10-15 · accepted (Phase 3; details in `docs/plans/phase-3.md` §4, §9)*

**Decision.**
- **Outbox.** `libs/common-messaging` writes `outbox_message` rows with `JdbcClient` inside the caller's transaction (`propagation = MANDATORY`). Each service creates the table in its own Flyway migration from the lib's reference DDL.
- **Relay.** A `SmartLifecycle` poller with its own thread: `FOR UPDATE SKIP LOCKED` batches, publisher confirms, `published_at` set after the confirm, retries with `attempts` / `last_error`. At-least-once delivery.
- **Topology.** Topic exchange `mitpass.events`; routing key = event type (`registration.confirmed`). Producers declare only the exchange; consumers declare their own queues. Dev: `mitpass.events.tap` (bound to `#`, max 1000 messages) to see messages before consumers exist.
- **Format.** AMQP `messageId` = outbox id, `type` = event type, header `correlationId`. Body = envelope `{messageId, type, schemaVersion, occurredAt, aggregateType, aggregateId, correlationId, payload}`. Contracts are records in `common-messaging`; fields are only added, never renamed or removed.
- **P3 events.** event-service: `RegistrationConfirmed` (also on promotion, `viaPromotion: true`), `RegistrationCancelled`, `WaitlistPromoted`, `EventStateChanged`. auth-service: `UserRegistered` (owner: "do it here").

- **Startup.** The relay opens one connection when it starts, so the exchange (and the dev tap queue) exist before the first event (as built; Spring AMQP otherwise declares on first use).

**Consequences.** Consumers (P4+) must be idempotent on `messageId` and tolerate reordering across aggregates. Tests switch the relay off unless they test messaging.

---

## D12: Passes, pass tokens and their keys

*2026-10-16 · accepted (owner answers to `docs/plans/phase-4.md` §19; details in the plan §4–§7, as built §20). Built in Phase 4*

**Decision.**
- **State machine (owner).** ISSUED → ACTIVE → EXPIRED; ISSUED → EXPIRED; ISSUED/ACTIVE → REVOKED (enum table + `pass_history`, as D10). **ACTIVE = the live code is unlocked**, `mitpass.passes.code-unlock-before-start` (2 h 30 min = 2 h before doors open) before `startsAt`, as in the UI mockup's wallet. A pass issued after that time goes straight to ACTIVE in the issuing transaction. An ACTIVE pass stays ACTIVE if the event is rescheduled later or its LIVE is undone.
- **Pass number.** `<EVENTCODE>-<NNNNNN>-<C>`: per-event 6-digit sequence plus a Luhn mod 10 check digit over the whole number, with letters expanded to two digits as in ISIN (A = 10 … Z = 35). Every single-digit typo is caught; a letter typo about 9 times in 10 (found by `PassNumberTest`: a letter becomes two digits, and no single decimal check digit can catch every substitution among 36 symbols). At the gate (P5) the code part is also matched against the event.
- **Token.** Compact JWS, **ES256** (P-256), header `{alg, kid, typ: "mitpass-pass+jws"}`, claims `iss = mitpass-pass`, `pid`, `eid`, `sub`, `iat`, `exp`, `jti`, `knd` (`L` live / `S` static); `sid` reserved. A contract with scanners (P5) and the offline PWA (P7): claims are only ever added.
- **Ticket file name (owner, RL5, P5).** `<event title>_<holder name>_<pass number>.pdf`, ASCII `filename` plus UTF-8 `filename*`: searchable for students, unique for us.
- **Lifetimes.** Live token 45 s, refreshed every 30 s, only for ACTIVE passes. Static token (PDF, email) valid until `endsAt + 2 h`, only if the event's `staticQrAllowed` (event-service, default true).
- **Keys.** Separate from auth's: pass-service generates ES256 keys, stores the private half encrypted with the shared `KeyEncryptor` (now in `libs/common-security`), publishes `/.well-known/pass-jwks.json`, rotates on `POST /api/passes/keys/rotate` (ADMIN). A retired key stays published until the longest token it signed (`max_token_exp`) has expired, plus 5 min.
- **Access.** QR images, tokens and the PDF go to the holder only; the event's organizer and ADMIN see and revoke passes; the revocation feed (`GET /api/revocations?since=<cursor>`) is for VOLUNTEER, ORGANIZER and ADMIN.
- **Event data.** pass-service keeps event times and status (`event_ref`) from the messages; venue, colours, `staticQrAllowed` and `organizerId` come from `GET /api/events/{id}` with a 60 s cache.

**As built.** The JWK set lists the ACTIVE key first, then newest first (sorting by creation time alone shuffled two keys made in the same instant). Live tokens are minted per request and not stored; `exp − iat` is exactly 45 s (whole seconds). A holder asking for the live code just after the unlock time gets it at once (the pass is activated on the spot if the scheduler hasn't run yet).

**Consequences.** P5 verifies with the pass JWK set, checks `knd` and the revocation list, and needs the event LIVE. Live codes for demo events with far-off dates stay locked: demos create an event starting soon (or override the config).

---

## D13: Consumers: inbox queue, idempotency, reordering, minimal DLQ

*2026-10-16 · accepted (owner answers to `docs/plans/phase-4.md` §19). Built in Phase 4; **dead-letter handling completed in P7***

**Decision.**
- **Queue per consuming service.** Durable `<service>.inbox` bound to the routing keys it needs on `mitpass.events`; one listener thread (in publish order). The body is read as the D11 envelope by `common-messaging`'s `IdempotentConsumer`, not through a message converter.
- **Idempotency.** `processed_message (consumer, message_id)`, inserted with `ON CONFLICT DO NOTHING` in the handler's transaction; a duplicate is skipped and acknowledged. Business uniqueness backs it up (one pass per registration). Rows are kept 30 days.
- **Reordering.** Tombstones for things cancelled before they existed; state messages apply only if newer (`changedAt`).
- **Correlation.** The listener copies the message's `correlationId` into the MDC, so the consumer's own outbox events carry it on.
- **Minimal DLQ (owner: pulled forward from P7, to be completed there).** 3 attempts with backoff, then reject without requeue → `mitpass.events.dlx` → `<service>.inbox.dlq`. Helper in `common-messaging`. **P7 completes it:** DLQs for every consumer, inspect/replay tooling and an alert or health signal when a DLQ isn't empty.
- **`EventRescheduled` (owner).** event-service publishes it when `startsAt` / `endsAt` change on a non-DRAFT event; pass-service moves its unlock and expiry times.

**As built.** `IdempotentConsumer` and `EnvelopeReader` are auto-configured by `common-messaging` (`InboxQueues.inbox(...)` returns the `Declarables`); a poison message reached `pass-service.inbox.dlq` after three attempts in `PassFlowTest`, and a message republished live with the same `message_id` was skipped (exit demo).

**Consequences.** checkin-service (P5) and notification-service (P7) declare their inboxes with the same helper. Until P7, a dead-lettered message just sits in the DLQ (visible in the RabbitMQ UI) and is replayed by hand.

---

## D14: Check-in, and who owns which data

*2026-10-16 · accepted (owner answers to `docs/plans/phase-5.md` §19, Q6 and RL7 the same day). Built in Phase 5 (as built: plan §21)*

**Decision.**
- **Source of truth.** Each fact has one owner: auth-service owns users and roles; event-service owns events, their status and times, sessions, gates, volunteer assignments and registrations; pass-service owns passes, their validity and revocations, and signing keys; checkin-service owns scans, attendance and presence. A copy held by another service (pass-service's `event_ref`, checkin-service's `pass_ref` and `event_ref`) is a **read-only cache** that is filled from the owner's events, never edited by hand, and can be rebuilt from the owner. When a copy and its owner disagree, the owner wins.
- **How copies stay in step.** The owner writes its event in the same transaction as the change (outbox, D11). The relay retries until RabbitMQ confirms. The consumer's durable inbox keeps messages while the consumer is down, and the consumer applies them idempotently (D13). A message is therefore never lost once the consumer's queue exists. The remaining gaps are: data from before the consumer's queue first existed, a message parked in a DLQ, and manual interference (purged queue, restored database). Phase 5 covers the first with a "Sync passes" action per event.
- **Automatic reconciliation (owner, Q6: "manual means human error is possible").** checkin-service's `PassCopyReconciler` asks the owners on its own, with no button to press: every 60 s it pulls the revocation feed from a stored cursor; every 5 min (and when an event goes LIVE) it re-reads the events in the scanning window and all their passes; during a scan, a genuine but unknown pass is fetched on the spot. Background calls use a **service identity** (`SERVICE` role tokens from auth-service, internal only; see D7). The manual "Sync passes" stays as an admin tool only.
- **Fail safe.** A missing pass in checkin-service's copy gives `UNKNOWN_PASS` (amber: the volunteer asks the organizer). A missing *revocation* would let a revoked pass in, so revocations are the direction that must be reconciled first.
- **Pipeline (owner).** Scanner assignment first, then decode → signature → expiry → event match → LIVE → session → pass known → not revoked → duplicate. Fifteen result codes (`docs/plans/phase-5.md` §5).
- **Single entry (owner).** pass-service records `checkedInAt` / `usedAt` from `AttendanceMarked`; no new pass state.
- **Per-event settings (owner).** `reentryAllowed` and `certificateMinSessions` live in event-service.

- **pass-service's copy too (owner, RL7).** `EventCopyReconciler` re-reads, every 5 min, the status and times of every event that still has valid passes (public catalogue, no token) and applies differences through the message handlers, so a lost cancellation still runs the revocation saga.

**As built.** checkin-service: a pipeline of nine step beans in `@Order` (scanner, signature or typed number, expiry, event match, LIVE, session, pass known, not revoked), then one transaction (presence row created if missing and locked `FOR UPDATE`, `UNIQUE (pass_id, session_id)` attendance, presence history, scan log, `AttendanceMarked` outbox row). 20 simultaneous scans gave 1 ACCEPTED three runs in a row, and only one also without the lock. Copies: `pass_ref` (REVOKED is sticky; a revocation for an unseen pass leaves a bare REVOKED row), `event_ref` (newest `changedAt` wins). `PassCopyReconciler`: revocation feed from a stored cursor every 60 s; window sync every 5 min, at startup and right after LIVE; an unknown but genuine token fetches its pass during the scan; a typed-number miss re-syncs its event at most every 30 s; jobs take a PostgreSQL advisory lock. Verified live: a pass issued before the queue existed reached the copy about 20 s after startup with no scan; a revocation whose message was purged was caught by the feed after a restart; a cancellation purged from pass-service's queue still revoked the pass within 30 s of its restart.

**Consequences.** A new service that copies another's data adds that copy to the owner table in PROJECT.md §3, says how it is rebuilt, and gets a reconciler: owners are re-read on a timer, nobody presses a button.

---

## D15: The finished portal (Phase 6)

*2026-10-17 · accepted (owner answers to `docs/plans/phase-6.md` §17 Q1–Q4; draft events Q12 the same day). Built in Phase 6 (as built: plan §18)*

**Decision.**
- **Styling (owner, Q1).** Hand-written CSS with design tokens (CSS custom properties taken from the UI mockup), redefined for dark mode. No Tailwind and no Node build step. The owner asked which option was faster and less work. The answer was plain CSS: no plugin, Node download, lock file or CI change; the mockup's CSS turns straight into tokens; and the portal is small (about 25 templates, 20 components). JS libraries (`htmx.org`, `html5-qrcode`) are WebJars pinned in the root POM (D1). Fonts are self-hosted woff2 files (OFL).
- **HTTPS for phone cameras (owner, Q2).** A self-signed PKCS#12 certificate made by `scripts/make-dev-cert` with the project JDK's `keytool` (SANs for localhost and the laptop's LAN IPs). The portal serves it under the `lan` profile on :8443; each phone accepts the warning once. No mkcert, because `mkcert -install` changes the system trust store. Only the portal faces the phone, so only the portal gets HTTPS.
- **Health overview (owner, Q3).** api-gateway serves `GET /api/admin/health` (ADMIN). It reads the instances from Eureka (`ReactiveDiscoveryClient`) and each one's `/actuator/health` with a 2 s timeout (status and component names only; `show-components: always`, details hidden). The gateway authorizes this endpoint itself, because here it is the service (D7). The portal still calls only the gateway (D9).
- **i18n (owner, Q4).** English and Tamil bundles (`messages.properties`, `messages_ta.properties`) with a cookie locale resolver and a language switch. The Tamil text is drafted by the agent and checked by the owner. Lifecycle labels (RL8) and result-code messages come from the bundles; the APIs keep their codes.
- **Draft events (owner, 2026-10-17).** event-service shows a DRAFT event only to its organizer and admins (`DraftVisibility`): left out of `GET /api/events` for anyone else, and `GET /api/events/{id}` and `/sessions` answer 404, as for an unknown id (a 403 would confirm the event exists). Until now drafts were public, which leaked unpublished events. No service depends on reading drafts: registrations, passes and scans need a published event.
- **Token refresh.** The portal refreshes the access token 60 s before it expires (`mitpass.portal.refresh-ahead`; at most a quarter of the token's lifetime, so short demo tokens aren't refreshed on every request), under a per-session lock with a re-read after the lock is taken, so parallel requests never reuse a refresh token (that would trigger D7's reuse detection and end the session). A 401 triggers one refresh and one retry.

**As built.** web-portal: `tokens.css` (mockup colours, type and spacing as custom properties, redefined under `[data-theme=dark]` and `prefers-color-scheme`) + `portal.css` (components, `:lang(ta)` rules: no letter-spacing, long words wrap); static files content-hashed with a one-year cache; self-hosted fonts with their OFL licences. `CurrentUserInterceptor` puts the user, theme and language links in every model (error pages too, through `PortalAdvice`). `CookieLocaleResolver` (`mitpass-lang`, default from the browser) with a `?lang=` switch that accepts only `mitpass.portal.supported-locales`; both bundles hold the same ~445 keys, Bean Validation messages included (`I18nTest`). `TokenRefresher` takes the session mutex, re-reads the user from the session, refreshes, and saves the context through the `SecurityContextRepository`; `GatewayClient` retries once after a 401. With 1-minute tokens the first version refreshed on every request, because the 60 s window covered the whole lifetime (95 rotations in 3.5 min, none reused); the window is now capped at a quarter of the lifetime (computed from the token's `iat`/`exp`), and the same run made 4 rotations. api-gateway: `AdminHealthController` over the composite `ReactiveDiscoveryClient`, a `WebClient` with `mitpass.gateway.admin-health.timeout` (2 s), `/api/admin/**` `hasRole(ADMIN)` with a ProblemDetail 403; a stopped pass-service showed DOWN on the next call. event-service: `DraftVisibility` (a JPA `Specification` for lists, `canSee` for single reads), `allowedTransitions` on `EventSummaryResponse`. auth-service: the verification link opens `http://localhost:8090/verify-email?token=…`, where a button POSTs the token (a link preview can't spend it). The portal also sends `size=100` for an organizer's own events, because the list showed only the API's default 20.

**Consequences.** PROJECT.md §4 no longer lists Tailwind, and §P6 names keytool instead of mkcert. A new LAN needs the certificate made again (the IP is in the SANs). Pages are plain MVC endpoints that also answer HTMX requests with fragments, so every page still works without JavaScript.

# Phase 5 plan: Scan, Verify & Attendance

> **Source:** `PROJECT.md` §P5 (including the portal slice from T21 and `docs/DECISIONS.md` D9). **Tracked in:** `log.md` as T07, sub-tasks T07.1–T07.13 (same numbering as the steps in §17).
> **Written:** 2026-10-16, after Phase 4 was merged to `main` (PR #4) and branch `phase5` was created from it. This plan is a guide: if a step turns out simpler or different in practice, do the sensible thing and record the deviation in `log.md` and in a closing "As built" section here.
> **Status:** all questions answered on 2026-10-16 (Q6: automatic reconciler + service identity, §4a/§4b). Pre-phase commit `ed5b989` by the owner. Building. The owner accepted Q1–Q4 on 2026-10-16. Q4 came with a condition: extra codes are fine only for unique cases, so each one is justified in §5. The answers are recorded in PROJECT.md §3/§P5 and `docs/DECISIONS.md` D14 (T07.1). **Open:** Q6, the consistency safety net (§4a). The RL6 fix is part of this phase (T07.0, §20). Q6 (resolved, see above).

## 1. Goal and exit criteria

**Goal:** a volunteer at a gate submits what the attendee shows (a live or static QR token, or a typed pass number) and gets an authoritative, typed answer in milliseconds. Each scan runs through a verification pipeline whose steps are separate beans. An accepted scan writes attendance, a scan log row and the presence change in one transaction that cannot double-count, even when two volunteers scan the same pass in the same millisecond. Organizers see attendance per event, session and gate, export it as CSV, and see who qualifies for a certificate. A thin `web-portal` slice shows the scan page, the result and the attendance page.

**Exit demo** (all must pass before T07 is marked done):

1. `discovery-server`, `auth-service`, `event-service`, `pass-service`, **`checkin-service` (:8084)**, `api-gateway` and `web-portal` start on compose infra. Eureka lists them. The RabbitMQ UI shows `checkin-service.inbox` bound to `pass.issued`, `pass.revoked`, `event.state-changed`, with its `.dlq`, and `pass-service.inbox` gains `attendance.marked`.
2. **Every result code** (§5) is produced by a request in `docs/http/phase-5.http`, with the expected HTTP status, `result`, message and colour. One `ScanVerificationTest` case per code proves the same in the build.
3. **Race:** `DoubleScanRaceTest` fires N = 20 concurrent scans of one pass at one session: exactly **1 `ACCEPTED`** and **19 `ALREADY_CHECKED_IN`**, one `attendance` row, 20 `scan_log` rows, one `AttendanceMarked` outbox row. Run three times in a row, same numbers. The `.http` file shows the same with two requests sent back to back.
4. **Anti-fraud:** a live token older than 45 s (+ 10 s skew) → `EXPIRED_TOKEN`; one changed character → `INVALID_SIGNATURE`; a revoked pass → `REVOKED` (revocation reaches checkin-service by message within about 1 s); a pass for another event → `WRONG_EVENT`; a volunteer not assigned to the event → `UNAUTHORIZED_SCANNER`; assigned to another gate → `WRONG_GATE`; an event not LIVE yet → `NOT_YET_OPEN`.
5. **Check-out and re-entry:** OUT mode on a checked-in pass → `CHECKED_OUT` (presence OUT). Scanning IN again → `ACCEPTED` (re-entry) on an event with `reentryAllowed`, `REENTRY_NOT_ALLOWED` otherwise. Presence history shows each move.
6. **Sessions:** on a multi-session event (AIWS26-style, three sessions), a pass checks in once per session; the second scan in the same session → `ALREADY_CHECKED_IN`; the next session's scan → `ACCEPTED` with a new attendance row.
7. **Manual fallback:** `GET /api/scans/lookup?passNumber=…&gateId=…` returns the holder's name and the pass state for a visual check; a typo in the number → `INVALID_PASS_NUMBER` before any lookup (Luhn); confirming → `ACCEPTED` with `method = MANUAL`.
8. **`AttendanceMarked`** appears on the tap queue with the scan request's correlation id; pass-service records `checkedInAt` (and `usedAt` for a single-entry event) on the pass, visible in `GET /api/passes/{id}` and in the wallet.
9. **Reports:** `GET /api/attendance/events/{id}/summary` shows valid passes, checked in, %, inside now, per session and per gate; `…/export.csv` downloads one row per pass; `…/certificates` lists who attended at least N sessions.
10. **Portal (D9 slice) at http://localhost:8090:** the volunteer picks event + gate, types a pass number → sees the name → confirms → green ACCEPTED; enters it again → red ALREADY_CHECKED_IN; pastes a QR token → result; the organizer's attendance page shows the new count after a reload and the CSV link works. No token appears in any page; forms need CSRF.
11. The security matrix for checkin-service covers every endpoint, all cells green; `docs/security-matrix.md` updated. Swagger UI at :8080 lists checkin-service.
12. `./mvnw verify` green locally and in GitHub Actions.

## 2. Starting point and scope

**Built in Phases 1–4:** common libraries (D6 errors, D7 security, D11 outbox, D13 inbox + minimal DLQ), gateway, auth, event-service (gates and volunteer assignments since P2, sessions since P1, lifecycle + LIVE scheduler since P3), pass-service (ES256 pass tokens, `/.well-known/pass-jwks.json`, revocation feed, `PassIssued` / `PassRevoked`). `checkin_db` and the `checkin_svc` role exist since P1 (`infra/postgres/init.sql`), so **no `docker compose down -v` is needed**. Demo seed: volunteer `volunteer@mitpass.test` (Karthik, `…04`), gates MAIN/NORTH (TECHFEST26), OAT (RANG26), LAB (AIWS26, three sessions).

**Owner decisions for this phase** (answers to §19, 2026-10-16; recorded in PROJECT.md and D14):

| Topic | Default | PROJECT.md impact |
|---|---|---|
| Where checkin-service gets pass data (Q1) | A **local copy** (`pass_ref`) filled from `PassIssued` and `PassRevoked`, so a scan never calls pass-service. Passes issued before checkin-service first started are pulled once with `POST /api/attendance/events/{id}/sync` (organizer/ADMIN, via pass-service's existing `GET /api/passes?eventId=`) | §3 services table: checkin-service also consumes `PassIssued` (Q1) |
| "Single-entry pass used" (Q2) | Two new **attributes** on the pass in pass-service, `checkedInAt` (first check-in) and `usedAt` (set only for single-entry events); **no new state** | none; §3 Pass row unchanged |
| Re-entry and certificates per event (Q3) | event-service **V8**: `reentry_allowed` (default false) and `certificate_min_sessions` (null = all sessions). Single-entry = `reentry_allowed = false` | none ("re-entry is allowed only if the event allows it") |
| Result codes (Q4) | PROJECT.md's nine **plus six**: `CHECKED_OUT`, `NOT_CHECKED_IN`, `REENTRY_NOT_ALLOWED`, `EVENT_CLOSED`, `UNKNOWN_PASS`, `INVALID_PASS_NUMBER` | §P5 result-code list grows (Q4) |
| Pipeline order (Q4) | The **scanner check runs first** (before the token is even decoded), so an unassigned account learns nothing about a pass. Otherwise PROJECT.md's order | §P5 pipeline bullet reordered (Q4) |
| Undo LIVE after check-ins (RL3) | Stays parked. Built for now: the undo is allowed and attendance rows are kept; scans get `NOT_YET_OPEN` until the event is LIVE again | none |

**Out of scope (and where it lands):**

| Not in P5 | Phase | What P5 does instead |
|---|---|---|
| Camera scanner, flash, sound, vibration | P6 | Text field for a pass number or pasted token (D9) |
| Live dashboard, Redis counters, WebSocket | P7 | SQL counts on a page that updates on reload (D9) |
| Offline scans, `POST /scans/batch`, `DUPLICATE_OFFLINE` | P7 | `scannedAt` and `deviceId` are already stored per scan; the pipeline is a bean chain the batch endpoint reuses |
| DLQ inspect/replay, DLQ alert | P7 | checkin-service's inbox gets its `.dlq` through `InboxQueues` (D13) |
| Circuit breakers, fallback when pass-service is down | P8 | Not needed for scans: pass data is local (Q1). event-service calls are cached (§4) |
| Certificate PDFs | extension | Eligibility list only |
| Gate-specific passes (VIP gates) | — | No data for it: any gate of the event admits any valid pass |

## 3. Module layout after Phase 5

```
pom.xml                              # <modules>: + libs/common-pass, services/checkin-service
libs/common-pass/                    # NEW: PassNumber (moved from pass-service), pass-token constants + ES256 verifier
libs/common-messaging/               # + contract AttendanceMarked
services/event-service/              # + V8 (reentry_allowed, certificate_min_sessions), DTO fields, seed
services/pass-service/               # + V2 (checked_in_at, used_at), consumes attendance.marked, PassNumber from the lib
services/checkin-service/            # NEW :8084
platform/api-gateway/                # + checkin-service routes, Swagger entry
web-portal/                          # + scan page, manual confirm, result, organizer attendance page, CSV proxy
docs/http/phase-5.http               # NEW demo requests (every result code)
```

| Module | Base package | Key dependencies |
|---|---|---|
| `common-pass` | `com.mitpass.common.pass` | `nimbus-jose-jwt` (through `spring-security-oauth2-jose`, already managed by Boot), nothing Spring-specific |
| `checkin-service` | `com.mitpass.checkin` | `spring-boot-starter-webmvc`, `-data-jpa`, `-validation`, `-actuator`, `-restclient`, `flyway-core` + `flyway-database-postgresql`, `postgresql`, eureka client, springdoc, `caffeine`, `common-web`, `common-security`, `common-messaging`, `common-pass`; test: `-webmvc-test`, `-security-test`, `security-test-support`, Testcontainers PostgreSQL + RabbitMQ |

No new third-party versions to pin (D1).

**checkin-service packages:**

```
com.mitpass.checkin
├── domain/        Attendance, ScanLog, Presence, PresenceState (+ table), PresenceChange, PassRef, EventRef,
│                  ScanResult (codes + message + colour + HTTP status), ScanMode, ScanMethod
├── repository/    AttendanceRepository, ScanLogRepository, PresenceRepository, PassRefRepository, EventRefStore
├── verify/        ScanContext, ScanStep (interface), ScanPipeline, steps: ScannerAssignedStep, TokenDecodeStep,
│                  SignatureStep, ExpiryStep, EventMatchStep, EventLiveStep, SessionStep, PassKnownStep,
│                  NotRevokedStep; PassJwksCache
├── service/       ScanService (pipeline + CheckInTransaction), CheckInTransaction, ManualLookupService,
│                  AttendanceReportService, PassSyncService, CheckinEvents (outbox)
├── messaging/     CheckinInbox, CheckinInboxListener
├── client/        EventCatalogClient (detail, gates, my assignments; cached), PassServiceClient (sync)
├── security/      CheckinSecurity (event owner)
└── web/           ScanController, AttendanceController, dto/...
```

## 4. Key decisions for this phase

| Topic | Choice | Why |
|---|---|---|
| Shared pass code | New `libs/common-pass`: `PassNumber` (format + ISIN-style Luhn, moved from pass-service unchanged), `PassTokens` (claim names, `typ`, `iss`, `knd` values) and `PassTokenVerifier` (Nimbus ES256 verify against a JWK source, `typ` + `iss` checks). pass-service's round-trip tests use the same verifier | AGENTS.md: code shared by several services goes in `libs/`. The claims are a contract (D12); one definition keeps signer and scanners in step |
| Pipeline | Chain of responsibility: `ScanStep` beans with `@Order`, each returning `continue` or a `ScanResult`. Order: **scanner assigned** → decode → signature (`kid` from the cached pass JWKS) → `exp` (10 s skew) → event match (`eid` = the gate's event) → event LIVE → session open → pass known → not revoked → (transaction) duplicate / presence. The manual path enters after "signature" with a pass looked up by number | PROJECT.md §P5. Scanner first (Q4): an unassigned account learns nothing about the pass |
| Scanner check | The gate (`gateId`, an event-service gate id) and the volunteer's assignments come from event-service: `GET /api/volunteers/me/assignments` with the volunteer's own token relayed (D7), cached 30 s per volunteer; `GET /api/events/{id}/gates` cached 60 s. Not assigned to the gate's event → `UNAUTHORIZED_SCANNER`; assigned to another gate of it → `WRONG_GATE` | event-service owns staffing (database per service). A 30 s cache means a removed volunteer can scan for at most 30 s more: documented, acceptable |
| Pass JWKS | `http://pass-service/.well-known/pass-jwks.json` through the load-balanced client, cached by `kid`; an unknown `kid` triggers one refetch, at most once per 30 s | Rotation (D12) without a restart, and a forged `kid` can't make us hammer pass-service |
| Event status | `event_ref` kept from `EventStateChanged` (newer `changedAt` wins, as pass-service). No row yet → one REST call to event-service seeds it | The LIVE check must be fresh; a 60 s cached detail could let scans in after COMPLETED |
| Event details | Code, sessions, `reentryAllowed`, `certificateMinSessions`, `organizerId` from `GET /api/events/{id}`, Caffeine 60 s | Display and policy data; edits are rare |
| Pass data (Q1) | `pass_ref` (pass id, number, event, registration, user, holder name/email, VALID/REVOKED) from `PassIssued` / `PassRevoked`. A valid signature for an unknown pass → `UNKNOWN_PASS` ("not synced yet: ask the organizer to sync"). Organizer sync pulls the event's passes from pass-service with the organizer's token | Scans work without pass-service up (P8's "graceful degradation" comes for free). Revocations arrive in about 1 s |
| Session choice | Event without sessions: one implicit session (`session_id = 0`). With sessions: the session running now (`startsAt ≤ now < endsAt`) wins; otherwise the next one whose doors are open (`startsAt − doors-open ≤ now`, 30 min). None → `NOT_YET_OPEN` (or `EVENT_CLOSED` after the last) | Overlapping windows (AIWS26 sessions 2 and 3) resolve to the session in progress |
| Check-in transaction | `CheckInTransaction` (`@Transactional`): `INSERT presence … ON CONFLICT DO NOTHING` (NOT_ARRIVED), then `SELECT … FOR UPDATE` on it (the **row lock**), then the presence state decides; on IN: `INSERT attendance` (**`UNIQUE (pass_id, session_id)`**), presence → IN + history, `AttendanceMarked` outbox row. A unique violation that still slips through → `ALREADY_CHECKED_IN` | Two layers, as PROJECT.md asks: the lock serializes, the constraint guarantees. The 20-thread race test proves it |
| Scan log | Every scan, accepted or not, gets a `scan_log` row with result, method, mode, device, gate, volunteer, `jti`, `scannedAt` (device clock) and `receivedAt` (server). Rejections are logged in their own short transaction; accepted ones inside the check-in transaction | The audit trail for disputes, and P7's offline merge needs `scannedAt` |
| Presence | `PresenceState implements LifecycleState` (D10): NOT_ARRIVED → IN; IN → OUT; OUT → IN. Re-entry (OUT → IN in the same session) is a policy check (`reentryAllowed`), not a table edge; a new session's first scan always counts. `presence_history` per move | PROJECT.md §3 Presence row |
| HTTP answer | `200` + `ScanResponse` for `ACCEPTED` and `CHECKED_OUT`. Every other code is a D6 `ProblemDetail` (`type …/problems/scan/<code-in-kebab>`) carrying the same fields as extensions (`result`, `message`, `color`, `scanId`, `attendee`): `409` for state conflicts (`ALREADY_CHECKED_IN`, `REENTRY_NOT_ALLOWED`, `NOT_CHECKED_IN`, `REVOKED`, `NOT_YET_OPEN`, `EVENT_CLOSED`, `WRONG_EVENT`, `WRONG_GATE`), `422` for unusable input (`INVALID_SIGNATURE`, `EXPIRED_TOKEN`, `INVALID_PASS_NUMBER`), `404` `UNKNOWN_PASS`, `403` `UNAUTHORIZED_SCANNER` | PROJECT.md's flow diagram (200 / 409) and D6. One shape for the portal and the P6/P7 scanners |
| Colours | `GREEN` (accepted, checked out), `AMBER` (needs a human: `ALREADY_CHECKED_IN`, `NOT_YET_OPEN`, `WRONG_GATE`, `UNKNOWN_PASS`, `NOT_CHECKED_IN`), `RED` (refuse: everything else) | UI reference: green/amber/red gate results |
| Static tokens | Accepted like live ones (they were only minted if the event allows it, D12); `method = QR_STATIC` in the log | PDF tickets must work at the gate |
| `AttendanceMarked` | Published on the first IN per pass and session (not on re-entry or check-out). Fields `attendanceId, passId, passNumber, registrationId, eventId, sessionId, userId, gateId, gateCode, volunteerId, method, checkedInAt, scannedAt, singleEntry` (`attendance.marked`) | PROJECT.md §P5; notification-service (P7) and the dashboard reuse it |
| pass-service reaction (Q2) | `attendance.marked` → set `checked_in_at` if empty; if `singleEntry`, set `used_at`. Idempotent (D13); a pass already EXPIRED/REVOKED just records the time | "Marks single-entry passes as used" without a new state |
| Reports | SQL aggregates in checkin-service: valid passes (`pass_ref` VALID), distinct checked-in passes, %, inside now (presence IN), per session and per gate counts. CSV: `passNumber,holderName,holderEmail,sessionsAttended,firstCheckIn,lastGate,presence`, streamed, `text/csv; charset=UTF-8`, filename `<EVENTCODE>-attendance.csv`. Certificates: attended ≥ `certificateMinSessions` (null = all sessions; 1 for an event without sessions) | PROJECT.md §P5 reports |
| Who may do what | Scans and lookup: VOLUNTEER (and the assignment check). Reports, export, certificates, sync: the event's organizer or ADMIN (`@checkinSecurity.ownsEvent(#eventId)`, `organizerId` from the cached catalogue, as pass-service). `GET /api/attendance/me`: any logged-in user, own rows only | PROJECT.md: scans VOLUNTEER only; ownership as D7 |

## 4a. Source of truth and consistency of the pass copy

**Owner of each fact** (PROJECT.md §3, D14): pass-service owns whether a pass exists and is valid; event-service owns event status, sessions, gates and assignments; checkin-service owns scans, attendance and presence. checkin-service's `pass_ref` and `event_ref` are **read-only caches** of the owners' data. They are never edited by hand and can always be rebuilt from the owner. If they disagree with the owner, the owner wins.

**What keeps the copy in step without any job:** the owner's outbox row commits with the change (D11), the relay retries until RabbitMQ confirms, checkin-service's durable inbox holds messages while it is down, and the consumer is idempotent (D13). Once the inbox queue exists, no message is lost.

**Where it can still drift:**

| Gap | Effect | Covered by |
|---|---|---|
| Passes issued before checkin-service's queue first existed | Scan says `UNKNOWN_PASS` (amber, safe) | "Sync passes" per event (§7) |
| A `PassIssued` parked in the DLQ | `UNKNOWN_PASS` (safe) | DLQ replay (P7) |
| **A `PassRevoked` lost or parked in the DLQ** | **A revoked pass is let in (unsafe)** | nothing automatic yet |
| An `EventStateChanged` lost | Scans accepted after COMPLETED, or refused while LIVE | REST seed only when no row exists |
| Manual interference (purged queue, restored DB) | any of the above | nothing automatic yet |

**Q6 resolved (owner, 2026-10-16): option (a). Close both gaps automatically; don't rely on people pressing a button ("manual means human error").** checkin-service gets a `PassCopyReconciler`. It calls the owners with a **service identity** (§4b) and runs without anyone pressing anything:

| Job | When | What | Closes |
|---|---|---|---|
| Revocation catch-up | every 60 s, and at startup | `GET /api/revocations?since=<cursor>` (pass-service, built in P4 for this), page by page; each entry → `pass_ref` REVOKED. The cursor is stored in `sync_cursor` | **lost or parked `PassRevoked`** (the unsafe gap) |
| Scanning-window sync | every 5 min, at startup, and right after an event goes LIVE | events in the scanning window (from event-service's public catalogue: status REGISTRATION_OPEN / REGISTRATION_CLOSED / LIVE, `startsAt ≤ now + 3 h`, not ended) → refresh `event_ref` status from the owner, then page through `GET /api/passes?eventId=` and upsert every pass | **passes issued before the queue existed**, a lost/parked `PassIssued`, a lost `EventStateChanged` |
| Single-pass fetch | during a scan, when the signature is genuine but the pass isn't in the copy | `GET /api/passes/{id}` with the service identity, upsert, continue the pipeline | the last seconds of lag; `UNKNOWN_PASS` then only means pass-service is unreachable *and* the pass is new |

The "Sync passes" endpoint stays, but only as an admin tool (it runs the same window sync for one event). Nothing depends on anyone pressing it. Each job takes a PostgreSQL advisory lock, so two checkin-service instances don't run the same job at once (ShedLock comes in P7). An owner change always wins: the sync overwrites the copy, except that REVOKED never turns back into VALID (pass-service has no such edge either).

## 4b. Service identity (Q6)

D7 relays the *caller's* token, but a scheduled job has no caller. auth-service therefore issues **service tokens**:

- `POST /api/auth/service-token {clientId, clientSecret}` → `{accessToken, expiresIn}`. The token is RS256, signed by the same key as user tokens, with `sub` = the client's fixed UUID, `name` = client id, `roles = [SERVICE]`, and **5 min** lifetime. No refresh token: the client just asks again.
- Clients are configured in auth-service: `mitpass.auth.service-clients.<id>: {subject, secret}`. The secret comes from an environment variable with a dev default (D8 style). Comparison is constant-time. A wrong secret gives `401 invalid-credentials`, logged.
- **Internal only:** the gateway refuses `/api/auth/service-token` (404), so only services on the internal network (through Eureka) can get a token.
- New role `SERVICE` in `common-security` (`Roles.SERVICE`, not in `Roles.ALL`, so it can't be granted to users through the admin API). pass-service allows `SERVICE` on `GET /api/revocations`, `GET /api/passes?eventId=` and `GET /api/passes/{id}`.
- checkin-service's `ServiceTokenProvider` caches the token until 30 s before it expires. A `ServiceAuthInterceptor` adds it to the reconciler's client, a separate load-balanced client from the token-relaying one.
- Security matrices: auth-service gains the endpoint row; pass-service gains a SERVICE column for its three readable endpoints (every other pass endpoint → 403 for SERVICE).

## 5. Result codes

PROJECT.md's nine codes plus six. The owner accepted extra codes only where they cover a case none of the others can express, so the last column says why each one isn't redundant.

| Code | HTTP | Colour | Message (shown to the volunteer) | Raised by | Why not an existing code (new codes only) |
|---|---|---|---|---|---|
| `ACCEPTED` | 200 | GREEN | Welcome, {name}. | transaction (first IN, or re-entry allowed) | |
| `CHECKED_OUT` | 200 | GREEN | {name} checked out. | transaction (OUT mode) | Successful *exit*. `ACCEPTED` would tell the volunteer the person went in |
| `ALREADY_CHECKED_IN` | 409 | AMBER | Already checked in at {time} at {gate}. | transaction | |
| `REENTRY_NOT_ALLOWED` | 409 | RED | This event doesn't allow re-entry. | transaction | The person *left* and wants back in, which is an event rule. `ALREADY_CHECKED_IN` means the pass is inside right now: a second copy or a double scan, so a fraud signal |
| `NOT_CHECKED_IN` | 409 | AMBER | Not checked in, so can't check out. | transaction (OUT mode) | Only exists in OUT mode. No IN code fits "there is nothing to check out" |
| `REVOKED` | 409 | RED | This pass was revoked. | not-revoked step | |
| `NOT_YET_OPEN` | 409 | AMBER | Doors aren't open yet (opens {time}). | event-live / session step | |
| `EVENT_CLOSED` | 409 | RED | This event has ended. | event-live / session step | `NOT_YET_OPEN` says "wait" (amber). After the end, waiting is pointless (red). This happens with a static PDF token, which stays valid 2 h after the end |
| `WRONG_EVENT` | 409 | RED | This pass is for {other event}. | event-match step | |
| `WRONG_GATE` | 409 | AMBER | You're assigned to {gate}, not this one. | scanner step | |
| `EXPIRED_TOKEN` | 422 | RED | Code expired: ask for the live code. | expiry step | |
| `INVALID_SIGNATURE` | 422 | RED | Not a valid MITPass code. | decode / signature step | |
| `INVALID_PASS_NUMBER` | 422 | RED | That pass number has a typo (check digit). | manual path | A typed number has no signature, so `INVALID_SIGNATURE` would be wrong. The fix is "retype it", not "suspect a forgery" |
| `UNKNOWN_PASS` | 404 | AMBER | Pass not found here yet: ask the organizer to sync. | pass-known step | A *genuine* signature for a pass the copy doesn't have (§4a), or a well-formed number nobody issued. Calling it invalid would turn away a real attendee |
| `UNAUTHORIZED_SCANNER` | 403 | RED | You aren't assigned to this event. | scanner step | |

A cancelled event needs no code of its own: its passes are revoked by the P4 saga, so the scan says `REVOKED`.

The enum holds code, status, colour and a message template; messages live in one place so P6's i18n bundles can take them over.

## 6. Data model

**checkin-service `V1__init.sql`** (`checkin_db`, owner `checkin_svc`):

| Table | Columns | Notes |
|---|---|---|
| `event_ref` | `event_id` PK, `code`, `status` null, `status_changed_at` null, `updated_at` | From `EventStateChanged` / REST seed |
| `pass_ref` | `pass_id` uuid PK, `pass_number` **unique**, `event_id`, `registration_id`, `user_id`, `holder_name`, `holder_email`, `status` CHECK (VALID, REVOKED), `issued_at`, `revoked_at`, `revoke_reason`, `updated_at` | Index `(event_id, status)`. Upserts tolerate reordering (a revoke before the issue leaves REVOKED) |
| `attendance` | `id` uuid PK, `pass_id`, `event_id`, `session_id` bigint NOT NULL (0 = whole event), `gate_id`, `gate_code`, `volunteer_id`, `method` CHECK (QR_LIVE, QR_STATIC, MANUAL), `checked_in_at`, `scanned_at` | **`UNIQUE (pass_id, session_id)`**; index `(event_id, session_id)` |
| `presence` | `pass_id` PK, `event_id`, `state` CHECK (NOT_ARRIVED, IN, OUT), `session_id`, `changed_at`, `version` | The row the transaction locks |
| `presence_history` | id, `pass_id`, `from_state`, `to_state`, `changed_at`, `actor`, `gate_id`, `scan_id` | D10 history |
| `scan_log` | `id` uuid PK, `received_at`, `scanned_at`, `volunteer_id`, `device_id`, `gate_id`, `event_id` null, `pass_id` null, `pass_number` null, `token_jti` null, `method`, `mode` (IN/OUT), `result`, `detail` | Indexes `(event_id, received_at)`, `(pass_id)` |
| `processed_message`, `outbox_message` | reference DDL (D11, D13) | |

**event-service `V8__checkin_settings.sql`:** `event.reentry_allowed boolean NOT NULL DEFAULT false`, `event.certificate_min_sessions smallint NULL CHECK (> 0)`. DTOs gain both (create, update, detail; fields only added). Seed: RANG26 allows re-entry; AIWS26 needs 2 of 3 sessions for a certificate.

**pass-service `V2__check_in.sql`:** `pass.checked_in_at timestamptz NULL`, `pass.used_at timestamptz NULL`. `PassResponse` gains `checkedInAt`, `usedAt`.

## 7. REST API (checkin-service; same paths through the gateway)

| Method | Path | Who | Success | Errors |
|---|---|---|---|---|
| POST | `/api/scans` `{token?, passNumber?, gateId, deviceId, scannedAt?, mode: IN\|OUT}` (exactly one of token / passNumber) | VOLUNTEER | 200 `ScanResponse {scanId, result, message, color, attendee {passId, passNumber, holderName}, sessionId, sessionTitle, checkedInAt}` | §5 codes as ProblemDetail; 400 validation |
| GET | `/api/scans/lookup?passNumber=&gateId=` | VOLUNTEER | 200 `{passNumber, holderName, passStatus, presence, eventCode}` for the visual check (writes a `scan_log` row with mode LOOKUP) | 422 `INVALID_PASS_NUMBER`, 404 `UNKNOWN_PASS`, 403/409 scanner codes, `WRONG_EVENT` |
| GET | `/api/attendance/events/{eventId}/summary` | event owner, ADMIN | 200 summary (§4 Reports) | 404 |
| GET | `/api/attendance/events/{eventId}/attendees?page=&presence=` | event owner, ADMIN | 200 `PageResponse<AttendeeRow>` | 404 |
| GET | `/api/attendance/events/{eventId}/export.csv` | event owner, ADMIN | 200 `text/csv`, `Content-Disposition: attachment` | 404 |
| GET | `/api/attendance/events/{eventId}/certificates` | event owner, ADMIN | 200 `[{passNumber, holderName, sessionsAttended, required}]` | 404 |
| POST | `/api/attendance/events/{eventId}/sync` | event owner, ADMIN | 200 `{added, updated}` | 503 `pass-service-unavailable` |
| GET | `/api/attendance/me` | authenticated | 200 `[{eventId, eventCode, sessionsAttended, sessionsTotal, certificateEligible, firstCheckIn}]` | — |

**New problem types:** `scan/<code>` for each rejection, `pass-service-unavailable` 503, `event-service-unavailable` 503 (reused name from pass-service).

## 8. Messages

- **checkin-service inbox** (`checkin-service.inbox` + `.dlq` via `InboxQueues.inbox(...)`, one listener thread, `IdempotentConsumer`):
  - `pass.issued` → upsert `pass_ref` VALID (unless already REVOKED).
  - `pass.revoked` → upsert `pass_ref` REVOKED (creates the row if the issue hasn't arrived yet).
  - `event.state-changed` → update `event_ref` if newer.
- **checkin-service publishes** `AttendanceMarked` (new contract in common-messaging, `attendance.marked`) through its outbox.
- **pass-service inbox** gains `attendance.marked` → `checked_in_at` / `used_at` (§4).

## 9. Library changes

- **`libs/common-pass` (new):** `PassNumber` moved verbatim from pass-service (package changes; pass-service imports it; `PassNumberTest` moves with it). `PassTokens` constants. `PassTokenVerifier.verify(String compact, JWKSource) → VerifiedPassToken {pid, eid, sub, kind, iat, exp, jti, kid}` or a typed failure (`MALFORMED`, `UNKNOWN_KID`, `BAD_SIGNATURE`, `WRONG_TYPE`). Expiry is left to the caller (skew is a policy).
- **`libs/common-messaging`:** `AttendanceMarked` contract.

## 10. Portal slice (`web-portal`)

D9 rules as before: tokens only in the session, CSRF on, PRG, all calls through the gateway.

| Page | Path | Who | Backend calls |
|---|---|---|---|
| Scan (screen 5, thin) | `GET /scan` | VOLUNTEER | `GET /api/volunteers/me/assignments`, gates of those events. Form: assignment/gate select, mode IN/OUT, one text field ("pass number or pasted code"), the last result (from flash) as a big coloured block with the code, message and name |
| Submit | `POST /scan` | VOLUNTEER | Input looks like a pass number → `GET /api/scans/lookup` → confirm page; otherwise → `POST /api/scans` with the token → redirect to `/scan` with the result in flash (PRG). Gate and mode are remembered in the session |
| Manual confirm (screen 6, thin) | `GET /scan/confirm` → `POST /scan/confirm` | VOLUNTEER | Shows name, pass number, state, presence with **Confirm** / **Cancel**; Confirm → `POST /api/scans {passNumber}` → redirect with the result |
| Attendance (screen 7, thin) | `GET /organizer/events/{id}/attendance` | ORGANIZER (owner), ADMIN | summary + attendees + certificates; "Export CSV" and "Sync passes" buttons |
| CSV proxy | `GET /organizer/events/{id}/attendance.csv` | ORGANIZER, ADMIN | byte pass-through (as the P4 PDF proxy) |
| Sync | `POST /organizer/events/{id}/attendance/sync` | ORGANIZER, ADMIN | `POST …/sync` → redirect with a flash |

- Header: "Scan" for volunteers; "My events" rows gain an "Attendance" link. The wallet shows "Checked in at …" when `checkedInAt` is set.
- `deviceId` = `portal-` + a random id kept in the HTTP session.
- D9 rows for P5 already exist (scan page, attendance on reload). New row only if one is needed while building.
- Tests: role checks (STUDENT → 403 on `/scan`), CSRF on each form, PRG, no token in the rendered result page, pass-number vs token routing, the colour class per result.

## 11. Gateway and other services

- Gateway: route `checkin-service` `Path=/api/scans/**,/api/attendance/**`; `checkin-service-openapi` `/docs/checkin-service`; Swagger entry. `EdgeSecurityTest`: `POST /api/scans` without a token → 401 at the edge.
- event-service: V8, DTO fields, seed. Its `SecurityMatrixTest` doesn't change (no new endpoints).
- pass-service: V2, `attendance.marked` binding + handler, `PassResponse` fields, `PassNumber` import from `common-pass`. Matrix unchanged.
- auth-service: nothing.

## 12. Configuration additions

```yaml
# checkin-service
server.port: 8084
spring.datasource.url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/checkin_db   # checkin_svc
mitpass:
  messaging: { tap-queue.enabled: false }
  checkin:
    token-clock-skew: 10s
    doors-open-before-session: 30m
    assignment-cache-ttl: 30s
    event-cache-ttl: 60s
    jwks-refetch-min-interval: 30s
    csv-zone: Asia/Kolkata
```

Tests: relay off unless a test checks messaging, listener `auto-startup: false` except in the consumer tests, fixed `Clock`, fake catalogue and JWKS.

## 13. Ports and running locally

| Port | What |
|---|---|
| 8084 | **checkin-service** (new) |
| 8080–8083 / 8090 / 8761 | unchanged |

Start order: discovery → auth → event → pass → **checkin** → gateway → portal. Start checkin-service before issuing passes you want to scan, or press "Sync passes" on the attendance page. For the demo, `docs/http/phase-5.http` creates an event starting about 25 min ahead (it goes LIVE 30 min before its start), a gate, assigns the volunteer, registers students and scans. A second event with three short sessions covers the session cases.

## 14. Concurrency test (`DoubleScanRaceTest`)

`@SpringBootTest` + Testcontainers PostgreSQL, Hikari pool 25, catalogue and JWKS faked, a LIVE event, one pass. 20 threads wait on a `CountDownLatch`, then call `ScanService.scan` with the same token and gate. Assert: 1 `ACCEPTED`, 19 `ALREADY_CHECKED_IN`, `attendance` count 1, `scan_log` count 20, presence IN with one history row, one `outbox_message` of type `attendance.marked`. A second case runs the race through the transaction without the row lock (test-only subclass) to show the unique constraint alone still yields one row (the others fail with a constraint error mapped to `ALREADY_CHECKED_IN`).

## 15. Tests

| Test | Kind | Proves |
|---|---|---|
| `PassNumberTest` (moved), `PassTokenVerifierTest` (common-pass) | unit | as P4; verify OK, tampered header/payload/signature, unknown kid, wrong `typ`/`iss` |
| `PresenceStateTest`, `ScanResultTest` | unit | allowed moves and `allowedStates`; every code has status, colour and message |
| `SessionResolverTest` | unit | no sessions, running session wins, doors-open window, between sessions, after the last |
| **`ScanVerificationTest`** | `@SpringBootTest` + Testcontainers | **one case per result code** (§5), plus static token, manual path, OUT mode |
| **`DoubleScanRaceTest`** | Testcontainers | §14 |
| `CheckinConsumerTest` | Testcontainers PostgreSQL + RabbitMQ | `PassIssued` / `PassRevoked` / `EventStateChanged` update the copies; duplicate delivery skipped; revoke-before-issue stays REVOKED |
| `AttendanceReportTest` | Testcontainers | summary numbers, per session/gate, CSV columns and escaping, certificates with N = 2 of 3 |
| `PassSyncTest` | Testcontainers + fake pass-service | sync adds missing passes, keeps revoked ones |
| `SecurityMatrixTest` (checkin-service) | `security-test-support` | every §7 row × anonymous, student, volunteer (assigned/not), organizer (owner/other), admin |
| pass-service `AttendanceMarkedHandlerTest` | Testcontainers | `checkedInAt` set once, `usedAt` only for single-entry, duplicate skipped |
| event-service `EventServiceTest` additions | existing | V8 fields saved and returned; `certificateMinSessions` > sessions → 400 |
| Portal `ScanPagesTest`, `AttendancePagesTest` | existing harness | §10 |

## 16. Documentation to update at T07.13

`README.md` (checkin-service in the start order and ports, the gate demo, the sync button), `PROJECT.md` (§P5 ticks + status; §3 tables if Q1/Q4 are accepted), `docs/DECISIONS.md` (new **D14 check-in**: pipeline order, result codes, transaction, pass copy; D9 row if any), `docs/security-matrix.md`, `docs/http/phase-5.http`, AGENTS.md parts table (`libs/common-pass`; QR row: the verifier lives there), "As built" here, `docs/REVIEW-LATER.md` (RL3 note on what P5 built), `log.md`.

## 17. Steps (log.md T07.1–T07.13)

Each step ends with `./mvnw -pl <module> -am verify` green, a full `./mvnw verify` after any step that touches `libs/`, and a `log.md` entry.

| # | Step | Size | Done when |
|---|---|---|---|
| T07.1 | **Owner decisions recorded** (§19) in PROJECT.md and DECISIONS (D14 draft) before code | S | this plan's §2 matches the answers |
| T07.2 | **`libs/common-pass`**: `PassNumber` moved, `PassTokens`, `PassTokenVerifier`; pass-service switched over | S | `PassNumberTest`, `PassTokenVerifierTest`, pass-service suite green |
| T07.3 | **Contracts + settings:** `AttendanceMarked`; event-service V8 + DTOs + seed; pass-service V2 + `attendance.marked` handler + `PassResponse` fields | M | event- and pass-service suites green, `AttendanceMarkedHandlerTest` green |
| T07.4 | **checkin-service skeleton:** module, app, config, V1, Testcontainers base, Eureka, gateway routes + Swagger + `EdgeSecurityTest` case | M | context test green; live: registers in Eureka, Swagger at :8080 |
| T07.5 | **Copies:** inbox + DLQ, `pass_ref` / `event_ref` handlers, `EventCatalogClient` (+ caches), pass JWKS cache | M | `CheckinConsumerTest` green |
| T07.6 | **Domain:** `PresenceState`, `ScanResult`, `SessionResolver`, entities | S | `PresenceStateTest`, `ScanResultTest`, `SessionResolverTest` green |
| T07.7 | **Pipeline:** the step beans and `ScanPipeline`, scan log for rejections | M | `ScanVerificationTest` rejection cases green |
| T07.8 | **Check-in transaction:** presence lock, attendance, check-out, re-entry, `AttendanceMarked` outbox | M | `ScanVerificationTest` all green; **`DoubleScanRaceTest` green 3 runs** |
| T07.9 | **Manual path:** lookup + `passNumber` scans | S | manual cases green |
| T07.10 | **Reports + sync:** summary, attendees, CSV, certificates, `/me`, sync | M | `AttendanceReportTest`, `PassSyncTest` green |
| T07.14 | **Service identity (Q6):** `Roles.SERVICE`, auth-service `POST /api/auth/service-token` + config + tests, gateway 404 for it, pass-service SERVICE access on the three read endpoints | M | `ServiceTokenTest` green; auth and pass matrices green; `EdgeSecurityTest` case |
| T07.15 | **Reconciler (Q6):** `sync_cursor`, revocation catch-up, scanning-window sync, single-pass fetch, advisory locks, `ServiceTokenProvider` | M | `PassCopyReconcilerTest` green: a revocation that never came by message is caught within one run; passes of an event that never came by message are present after one run; an unknown pass with a genuine signature is accepted after the single fetch |
| T07.11 | **Security matrix** + `docs/security-matrix.md` | S | every cell green |
| T07.12 | **Portal slice:** scan, confirm, result, attendance page, CSV proxy, wallet line | M | portal tests green |
| T07.13 | **Exit demo + docs** (`phase-5.http`, README, PROJECT ticks, D14, as built, log) | M | all twelve exit criteria pass; nothing left running |

Order: T07.1 → T07.2 → T07.3 → T07.14 → T07.4 → T07.5 → T07.6 → T07.7 → T07.8 → T07.9 → T07.10 → T07.15 → T07.11 → T07.12 → T07.13. T07.6 can run alongside T07.5. (T07.14/T07.15 were added after Q6. IDs aren't renumbered, so they sit out of numeric order.)

## 18. Gotchas to check at build time

1. **Thymeleaf JavaScript inlining** drops the default text after an inlined expression (RL6: `/*[[${s}]]*/ 30 * 1000)` became `30)`, everything up to the closing parenthesis). Inline a finished value into its own `const`, and test the rendered number.
2. **NULL in a unique constraint:** `UNIQUE (pass_id, session_id)` would let two NULLs through, so "no session" is `0`, not NULL.
3. **`INSERT … ON CONFLICT DO NOTHING` + `FOR UPDATE`** must be two statements in one transaction; the second scanner waits on the lock, then reads the committed IN.
4. **Read Committed and the unique violation:** a constraint error aborts the PostgreSQL transaction, so the fallback mapping to `ALREADY_CHECKED_IN` happens outside it, with the rejection logged in a new transaction.
5. **Token relay for the assignment lookup:** the call runs on the request thread with the volunteer's token (`BearerTokenRelayInterceptor`); the race test runs scans on pool threads, so it fakes the catalogue instead.
6. **Caching per volunteer:** the Caffeine key is the volunteer id, never the token.
7. **Load-balanced clients** `@Bean(defaultCandidate = false)` + `@LoadBalanced` (log.md note).
8. **CSV:** quote every field, double inner quotes, and prefix cells starting with `=`, `+`, `-`, `@` with `'` (spreadsheet formula injection: holder names come from users).
9. **Moving `PassNumber`:** the class name and behaviour stay; only the package changes. Check nothing serializes the class name.
10. **Windows editing:** use the Write tool or Python with `encoding="utf-8", newline=""` (log.md).

## 19. Owner questions

Q1–Q5 resolved 2026-10-16:

1. **Q1: Pass data in checkin-service.** Accepted: a local copy from `PassIssued` / `PassRevoked`, plus "Sync passes". The owner asked which side is the source of truth and whether anything keeps the copy consistent. Answer: pass-service is the source of truth (PROJECT.md §3, D14), and there is no scheduled reconciliation yet (§4a). That led to Q6.
2. **Q2:** accepted (`checkedInAt` / `usedAt`, no new state).
3. **Q3:** accepted (V8 settings in event-service).
4. **Q4:** accepted, on condition that extra codes only cover unique cases. Each one was checked (§5, last column), and none turned out redundant. `EVENT_CLOSED` no longer covers cancelled events, because the saga already makes those `REVOKED`.
5. **Pre-phase commit:** the owner commits; work waits for that. RL6 is part of Phase 5 (T07.0).

6. **Q6: resolved 2026-10-16: (a)**, closing both gaps automatically ("manual means human error is possible; avoid relying on users"). Design in §4a/§4b; steps T07.14/T07.15. The options as they were put:

   **Q6: Scheduled reconciliation of the pass copy?**
   - **(a) Recommended: a reconciler in checkin-service.** Every minute it pulls pass-service's revocation feed from a stored cursor (`GET /api/revocations?since=`, built in P4 for exactly this), which closes the one unsafe gap. Every 10 minutes, and when an event goes LIVE, it compares each LIVE or soon-starting event's valid-pass count with pass-service and re-syncs that event if they differ. A background job has no user token (D7 relays the caller's token), so this needs a **service identity**: auth-service issues short-lived tokens to configured service clients (client-credentials style, role `SERVICE`), and pass-service lets `SERVICE` read the feed and the pass list. P7 (notification reminders) and P8 can reuse it. Cost: about 1–2 steps more (one auth-service endpoint, matrix rows, D7 update).
   - **(b)** No job. Rely on the outbox/inbox guarantees plus the manual "Sync passes" button, and park the gap in REVIEW-LATER. Smaller, but a lost revocation stays unnoticed.
   - **(c)** A middle way: the reconciler runs only on demand ("Sync passes" also pulls the revocation feed with the organizer's token). No service identity needed, but nothing happens unless someone presses the button.

## 20. T07.0: RL6 fixed as part of this phase (2026-10-16)

`docs/REVIEW-LATER.md` RL6 ("the live QR glitches") was reproduced by rendering the pass page's script with Thymeleaf. **Cause:** JavaScript inlining replaced `/*[[${refreshSeconds}]]*/ 30 * 1000` with just `30`, because Thymeleaf drops the default text after an inlined expression. The page therefore swapped the image every **30 ms**, not every 30 s, so each second it minted about 33 tokens and PNGs and the image kept re-painting. None of the suspected causes in RL6 was the real one. **Fix** (`web-portal`): the controller passes `refreshMillis`, and the script inlines it into its own `const`. It also preloads the next image off-screen and swaps it in only once it has loaded, so the code never blanks (RL6 option a). `PassPagesTest.liveQrRefreshesEveryThirtySecondsNotEveryThirtyMilliseconds` fails on the old template and passes now. RL6 suspected cause (b), the 400 → 280 px downscale with `pixelated`, is cosmetic and goes to P6 with the pass card.

## 21. As built: deviations and findings (2026-10-16)

Phase 5 was built as planned, with the owner's answers (§19, including Q6). Where the build differs from the sections above, this list is authoritative.

| Topic | Plan said | As built / why |
|---|---|---|
| Steps added after Q6 | — | T07.14 service identity, T07.15 `PassCopyReconciler` (§4a/§4b) |
| Window sync | compare counts, then re-sync | Always re-reads all passes of each event in the window (status included): simpler, also catches status differences, cheap at college scale |
| Event discovery | — | The window comes from event-service's public catalogue search (LIVE; or REGISTRATION_OPEN/CLOSED and starting within 3 h), so events checkin-service never heard about are found too |
| Manual miss | `UNKNOWN_PASS` | A typed number the copy lacks first re-syncs its event (at most once per 30 s per event), then `UNKNOWN_PASS` |
| `certificateMinSessions` | 400 when greater than the sessions | Not checked when saved (sessions are edited separately, so the check would go stale); reports use `min(N, sessions)` |
| Revoked stub rows | — | A revocation (feed or message) for a pass the copy hasn't seen creates a bare REVOKED row (`pass_number` nullable): a scan of that pass says `REVOKED`, not `UNKNOWN_PASS` |
| Gateway | refuse the service-token path | `InternalPathsWebFilter` answers 404 before security and routing, with the path normalized (case, `//`, percent-encoding); Spring 7 removed the trailing-separator option |
| Security matrix harness | — | **Bug found by the checkin matrix:** summary and CSV of an event without scans returned 500 (`max()` of no rows is NULL; `JdbcClient.single()` refuses a null). Fixed with a test. The harness counted the 500 as "allowed"; `SecurityMatrix` now fails any 5xx |
| Portal scan client | — | `GatewayClient.scan/lookup` read refusals (403/404/409/422 with a `result` field) as answers, not errors, so the page shows them in their colour |
| Mapping | MapStruct | Records with `from(...)`, as pass-service |
| RL7 (owner, mid-phase) | parked for after P8 | Built: pass-service `EventCopyReconciler` (§4a pattern for pass-service's own event copy), `mitpass.passes.reconcile.*`, `EventCopyReconcilerTest` (4) |
| Mockup pass number | `TECHFEST26-000042-7` used as a valid example | Its check digit is wrong (the valid one is `…-5`); found by the live demo, `phase-5.http` corrected. README/PROJECT keep it only as an illustration of the format |

**Verified live** (2026-10-16, compose infra with the earlier volume, jars on the project JDK; scripts `phase5_demo.py` and `rl7_demo.py` in the session scratchpad, same steps in `docs/http/phase-5.http`):

- **Exit criteria 1–11:** checkin-service in Eureka; `checkin-service.inbox` bound to the three keys, its `.dlq`, and `pass-service.inbox` bound to `attendance.marked`. Every result code produced through the gateway with its status and colour: NOT_YET_OPEN, UNAUTHORIZED_SCANNER, ACCEPTED (Priya, green), ALREADY_CHECKED_IN, WRONG_GATE, CHECKED_OUT, REENTRY_NOT_ALLOWED, NOT_CHECKED_IN, INVALID_SIGNATURE (one character), lookup + MANUAL accept, INVALID_PASS_NUMBER, WRONG_EVENT, UNKNOWN_PASS, REVOKED (message path), EXPIRED_TOKEN (56 s), EVENT_CLOSED (after COMPLETED). pass-service recorded `checkedInAt` / `usedAt`. Reports, CSV, certificates, `/me`; service token 404 at the gateway; Swagger lists checkin-service. Portal: scan page, typed number → confirm page with the name → red REENTRY_NOT_ALLOWED block, no token in pages, CSRF 403, organizer attendance page and CSV.
- **Owner Q6, both gaps, shown live:** (1) Priya registered while checkin-service had never run (its queue didn't exist); about 20 s after it started, her pass was in `pass_ref` (checked in the database, before any scan). (2) checkin-service stopped, Priya's pass revoked, `checkin-service.inbox` purged; after the restart the log said "Revocation catch-up applied 1 revocation(s)" and her static ticket scanned REVOKED.
- **RL7 shown live:** pass-service stopped, an event with one pass cancelled, `pass-service.inbox` purged (pass still ISSUED); 30 s after the restart: "copy says REGISTRATION_OPEN, event-service says CANCELLED", pass REVOKED (`EVENT_CANCELLED`).
- **First-run failures, all in the demo script, not the app:** a lookup before LIVE expected 200 (the app answered NOT_YET_OPEN, correctly), the mockup number's check digit (above), and a Windows console encoding error; the script's purge step also didn't run, so the purge was done by hand with the management API before the restart. Rerun of the affected steps: all passed.
- **Criterion 12:** full `./mvnw install` green locally (one rerun: the VS Code Java server deleted class files in `event-service/target` mid-build, the known log.md note). CI runs on the next push.

**Tests:** common-web 27, common-security 13, security-test-support 17, common-messaging 22, common-pass 14, discovery 1, gateway 21, auth-service 54, event-service 132, pass-service 59, checkin-service 54, web-portal 56 = **470**. Security matrix 410/410 (auth 90, event 168, pass 88, checkin 64).

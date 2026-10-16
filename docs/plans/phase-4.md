# Phase 4 plan: Pass Issuance & QR Generation

> **Source:** `PROJECT.md` §P4 (including the portal slice from T21 and `docs/DECISIONS.md` D9). **Tracked in:** `log.md` as T06, sub-tasks T06.1–T06.13 (same numbering as the steps in §17).
> **Written:** 2026-10-16, after Phase 3 was merged to `main` (PR #2) and branch `phase4` was created from it. This plan is a guide: if a step turns out simpler or different in practice, do the sensible thing and record the deviation in `log.md` and in a closing "As built" section here.
> **Status:** owner answered §19 on 2026-10-16 (all three defaults accepted; Q2 with "clearly mark that it is to be fully completed in P7"). Recorded in PROJECT.md §3/§P4/§P7 and `docs/DECISIONS.md` D12/D13.

## 1. Goal and exit criteria

**Goal:** every confirmed registration becomes a signed, scannable pass automatically, exactly once, even when RabbitMQ delivers a message twice. The pass carries a compact ES256 JWS that anyone can verify against pass-service's public JWK set, and one edited character makes verification fail. Passes follow their own state machine, revocations are published as a feed for scanners, and cancelling an event revokes all its passes (the first saga step). A thin `web-portal` slice shows the wallet, the live QR, the PDF ticket and the organizer's revoke button.

**Exit demo** (all must pass before T06 is marked done):

1. `discovery-server`, `auth-service`, `event-service`, **`pass-service` (:8083)**, `api-gateway` and `web-portal` start on compose infra. Eureka lists the six apps. The RabbitMQ management UI shows the queue `pass-service.inbox` bound to `mitpass.events` with its four routing keys, and the dead-letter queue `pass-service.inbox.dlq` (§19 Q2).
2. **Issuance:** a student registers for an event through the gateway, and within about 1 s `GET /api/passes/me` lists a pass in state ISSUED (or ACTIVE, §5) with a number like `TECHFEST26-000042-7` whose check digit is valid. The tap queue shows `PassIssued` carrying the registration request's correlation id.
3. **Signature:** the live QR PNG from `GET /api/passes/{id}/qr/live`, decoded with any phone QR app, is a compact JWS (`header.payload.signature`). `scripts/VerifyPassToken.java` (plain JDK, no project code) verifies it against `http://localhost:8080/.well-known/pass-jwks.json` and prints the claims. With one character of the payload changed, it prints `INVALID SIGNATURE`.
4. **Rotating QR:** the live token has `exp − iat = 45 s`. Two requests 30 s apart return different tokens (`jti`, `iat`). The portal's pass page swaps the image every 30 s.
5. **Static QR and PDF:** `GET /api/passes/{id}/ticket.pdf` downloads a one-page ticket with the event title, date and venue (with a map link), the holder's name and initials badge, the pass number and a static QR that verifies like the live one (valid until the event ends plus a grace period). For an event with `staticQrAllowed = false`, the static QR endpoint returns `409 static-qr-disabled` and the PDF has no QR, only the pass number and "show the live code in My passes".
6. **Consumer idempotency:** the same `RegistrationConfirmed` message published twice (republished from the management UI with the same `message_id`) leaves exactly one pass, and pass-service logs the duplicate. `ConsumerIdempotencyTest` shows the same thing for a sequential and a concurrent redelivery.
7. **Revocation:** organizer A revokes a pass. Its state becomes REVOKED with the reason, `PassRevoked` appears on the tap queue, and `GET /api/revocations?since=0` lists it with a cursor. A live-token request for that pass gets `409 pass-not-valid`. When a student cancels a confirmed registration, the pass is revoked (`REGISTRATION_CANCELLED`), and the promoted waitlisted student gets a new pass.
8. **Saga step:** organizer A cancels an event with passes. Within a few seconds every valid pass for it is REVOKED (`EVENT_CANCELLED`, trigger SYSTEM in the history), with one `PassRevoked` each.
9. **Key rotation:** `POST /api/passes/keys/rotate` (ADMIN) makes a new `kid`. The JWK set then lists both keys, and a static token signed before the rotation still verifies.
10. **Portal (D9 slice) at http://localhost:8090:** a student registers → "My passes" lists the pass (after a reload) → the pass page shows the pass card and a QR image that changes every 30 s → the PDF downloads. Organizer A opens the event's registrations page and revokes the pass → the student's pass page shows REVOKED and no QR. The page source never contains a token or a JWS. A form POST without its CSRF token gets 403.
11. The security matrix for pass-service covers every endpoint, all cells green, and `docs/security-matrix.md` is updated. Swagger UI at :8080 lists pass-service.
12. `./mvnw verify` is green locally and in GitHub Actions.

## 2. Starting point and scope

**Built in Phases 1–3:** common libraries (D6 errors, D7 security, D11 outbox + relay), discovery, gateway, auth-service, event-service with registrations, waitlist, lifecycle + scheduler, and the outbox. event-service already publishes `RegistrationConfirmed` (also on promotion), `RegistrationCancelled`, `WaitlistPromoted` and `EventStateChanged`. Until now they land only in the dev tap queue. `pass_db` and the `pass_svc` role already exist (`infra/postgres/init.sql`, P1), so **no `docker compose down -v` is needed**. For a clean demo, though, a fresh volume avoids old P3 registrations that have no pass (§13).

**Owner decisions for this phase** (answers to §19, 2026-10-16; recorded in PROJECT.md and `docs/DECISIONS.md` D12/D13):

| Topic | Default | PROJECT.md impact |
|---|---|---|
| ISSUED vs ACTIVE | **ISSUED** at creation. **ACTIVE** when the live code unlocks, `code-unlock-before-start` (default 2 h 30 min = 2 h before doors open) before `startsAt`, as the UI mockup's wallet says ("the scannable code appears 2 hours before doors open, so it can't be passed around early"). Before that, the live QR answers `409 code-locked` with `unlocksAt` | §3 Pass row gets the meaning of ACTIVE (Q1) |
| ISSUED → EXPIRED | Allowed (a pass whose event ended without the pass unlocking, for example after a reschedule). PROJECT.md lists only ACTIVE → EXPIRED | §3 Pass row gains one edge (Q1) |
| Static QR | Per-event switch `staticQrAllowed` (default **true**), owned by event-service (new column). Static token valid until `endsAt + static-valid-after-end` (2 h). Available from issuance, because the PDF ticket and the P7 email carry it | none (PROJECT.md already says "if the event allows it") |
| QR colour | Per-event `passColor` (hex, optional; default maroon `#7A1F2B` from the UI reference), owned by event-service, with a contrast check so scanners can still read it | none ("colors themed per event") |
| Dead-letter queue | **Minimal, pulled forward from P7; P7 completes it** (DLQs for every consumer, inspect/replay, alert when not empty). In P4: failed messages are retried 3 times, then go to `pass-service.inbox.dlq` instead of being lost or retried forever | §P4 bullet + §P7 "complete the dead-letter handling" bullet (Q2) |
| Reschedules | event-service publishes a new `EventRescheduled` when `startsAt` / `endsAt` change on an event that may have passes, so unlock and expiry times follow | §3 services table: one more event published by event-service (Q3) |
| Undo LIVE / later start | An ACTIVE pass stays ACTIVE (there is no ACTIVE → ISSUED edge). The live code just stays available a little longer. Check-in still needs a LIVE event (P5) | none |
| Pass per registration | One pass per registration (`UNIQUE registration_id`). A student who cancels and registers again gets a new registration and therefore a new pass and a new number; the old pass is REVOKED | none (follows D10) |
| Key encryption code | `KeyEncryptor` moves from auth-service to `libs/common-security`, because pass-service needs the same thing (AGENTS.md: shared code in `libs/`). The stored format is unchanged, so existing auth keys still decrypt | none |

**Out of scope (and where it lands):**

| Not in P4 | Phase | What P4 does instead |
|---|---|---|
| Scanning, verification pipeline, `AttendanceMarked`, "single-entry pass used" | P5 | The JWS claims and the JWKS are the contract checkin-service will verify against; the revocation feed is ready for it |
| Manual lookup by pass number | P5 | `PassNumber.isValid` (Luhn) exists and is tested; P5 moves it to a lib if checkin-service needs it |
| Pass and ticket emails, `.ics` | P7 | `PassIssued` is on RabbitMQ; notification-service consumes it later |
| Offline verification in the browser | P7 | ES256 + a public JWK set + a cursor-based revocation feed are exactly what the PWA needs |
| Pass card with countdown ring, glassmorphism | P6 | D9 shortcut: `<img>` reloaded by a plain 30 s timer |
| Circuit breaker on the event-service call | P8 | A short cache (§9); a failed call → 503 ProblemDetail |
| Per-session passes (`sid` claim) | P5 (if needed) | The claim is reserved and left out; one pass covers the whole event |
| Non-Latin names in the PDF | P8 | Built-in Helvetica (Latin-1); P8 embeds a Unicode font if the seed needs it |

## 3. Module layout after Phase 4

```
pom.xml                              # <modules>: + services/pass-service; pins zxing 3.5.4, openpdf 3.0.5
libs/common-security/                # + keys/KeyEncryptor (moved from auth-service)
libs/common-messaging/               # + consumer side: EventEnvelope, IdempotentConsumer, processed_message DDL,
                                     #   dead-letter topology, contracts PassIssued, PassRevoked, EventRescheduled
services/auth-service/               # uses KeyEncryptor from the lib (no behaviour change)
services/event-service/              # + V7 (static_qr_allowed, pass_color), EventRescheduled
services/pass-service/               # NEW :8083
platform/api-gateway/                # + pass-service routes, Swagger entry, public JWKS path
web-portal/                          # + My passes, pass page, QR/PDF proxy, organizer registrations + revoke
scripts/VerifyPassToken.java         # NEW: verifies a pass JWS with the JDK only (exit demo 3)
docs/http/phase-4.http               # NEW demo requests
```

| Module | Base package | Key dependencies |
|---|---|---|
| `pass-service` | `com.mitpass.pass` | `spring-boot-starter-webmvc`, `-data-jpa`, `-validation`, `-actuator`, `-restclient`, `flyway-core` + `flyway-database-postgresql`, `postgresql`, eureka client, springdoc, MapStruct, `common-web`, `common-security`, `common-messaging`, **`com.google.zxing:core` 3.5.4** (not `javase`: it pulls jcommander and jai-imageio, and P4 renders its own images anyway), **`com.github.librepdf:openpdf` 3.0.5**, `caffeine` (Boot BOM); test: `-webmvc-test`, `-data-jpa-test`, `-security-test`, `security-test-support`, Testcontainers PostgreSQL + RabbitMQ |

ZXing and OpenPDF aren't in the Boot BOM. They get pinned in the root POM's `dependencyManagement` (D1 rule) and listed in D1's "Pinned in the root POM" line. Both were checked on Maven Central on 2026-10-16: ZXing 3.5.4 (Nov 2025) and OpenPDF 3.0.5 (May 2026) are the latest releases.

**pass-service packages:**

```
com.mitpass.pass
├── domain/        Pass, PassStatus (+ transition table), PassChange (history), PassNumber (format + Luhn),
│                  EventRef, RevocationEntry, RevokeReason, SigningKey, SigningKeyStatus
├── repository/    PassRepository, PassChangeRepository, EventRefRepository, RevocationRepository,
│                  SigningKeyRepository, CancelledRegistrationRepository
├── keys/          PassKeyService (ES256 keys, rotation, JWK set)
├── token/         PassTokenService (mint live/static), PassTokenClaims
├── qr/            QrRenderer (ZXing matrix → PNG/SVG, logo, colours), QrStyle
├── ticket/        TicketPdfRenderer (OpenPDF)
├── service/       PassIssuer, PassLifecycleService (activate, expire, revoke), PassLifecycleScheduler,
│                  PassQueryService, PassEvents (outbox)
├── messaging/     PassInboxListener (@RabbitListener), InboxTopology
├── client/        EventCatalogClient (GET /api/events/{id}, cached)
├── security/      PassSecurity (holder / event owner)
└── web/           PassController, PassMediaController, RevocationController, PassKeysController, dto/...
```

## 4. Key decisions for this phase

| Topic | Choice | Why |
|---|---|---|
| Pass state machine | `PassStatus implements LifecycleState<PassStatus>` (common-web, as D10), `EnumMap` table (§5), one `transition(to, actor, trigger, reason)` on `Pass`, a `pass_history` row per move | D10: every stateful aggregate gets an enum table and a history table |
| Pass number | `<EVENTCODE>-<NNNNNN>-<C>`: the event code, a 6-digit per-event sequence, and a check digit. The check digit is Luhn mod 10 over the number with letters expanded to two digits (A = 10 … Z = 35, as in ISIN), so typos in the event code are covered too (as built: every digit typo, about 9 in 10 letter typos; see §20) | PROJECT.md format `TECHFEST26-000042-7`; plain Luhn would only cover the digits |
| Per-event sequence | `event_ref.last_pass_seq`, incremented with `UPDATE … SET last_pass_seq = last_pass_seq + 1 … RETURNING` in the issuing transaction | Gap-free per event, and the row lock serializes the rare concurrent issuances for one event |
| Event data in pass-service | `event_ref` (id, code, title, startsAt, endsAt, status, `status_changed_at`) is kept up to date from the messages. Display-only data (venue, map link, `staticQrAllowed`, `passColor`, `organizerId`) comes from `GET /api/events/{id}` through a load-balanced client with a 60 s Caffeine cache | Database per service: times and status drive pass-service's own state (scheduler, saga), so they are stored locally. Everything else is a REST query (PROJECT.md §3), with one call per minute per event at most |
| Signing keys | ES256 (P-256), generated with Nimbus, private half encrypted at rest with the shared `KeyEncryptor` (key from `MITPASS_KEY_SECRET`, same as auth-service). At most one ACTIVE key (partial unique index). Rotation `POST /api/passes/keys/rotate` (ADMIN) | PROJECT.md: separate keys from auth, ES256, own JWKS. Same proven pattern as D7 |
| How long a retired key stays published | Each key records `max_token_exp`, raised whenever it signs a token (only static tokens move it noticeably). A retired key stays in the JWK set until `max_token_exp + 5 min` | AGENTS.md "a rotated key stays in the JWKS until old tokens expire". Static tokens can live for weeks, so a fixed grace period would be wrong |
| JWS format | Header `{alg: ES256, kid, typ: "mitpass-pass+jws"}`. Claims `iss: "mitpass-pass"`, `pid` (pass id), `eid` (event id), `sub` (user id), `iat`, `exp`, `jti`, `knd` (`L` live / `S` static). `sid` is reserved for per-session passes and left out. Short claim names keep the QR small | PROJECT.md payload plus `iss`, `typ` and `knd`, which P5 needs to apply a different policy to static codes. Contract: claims are only ever added |
| Live token | Minted on each request, not stored: 45 s (`live-token-ttl`), refreshed by the client every 30 s. Only for ACTIVE passes | Screenshots stop working (PROJECT.md anti-fraud) |
| Static token | Minted on request (PDF, static QR endpoint): `exp = endsAt + 2 h`. Only for ISSUED or ACTIVE passes on events with `staticQrAllowed` | A printed or emailed ticket must work at the gate. A revoked pass is still caught by the revocation check in P5 |
| Who sees what | The pass card (`GET /api/passes/{id}`): holder, the event's organizer, ADMIN. **QR images, tokens and the PDF: the holder only.** Organizer pass list and revoke: the event's organizer and ADMIN. Revocation feed: VOLUNTEER, ORGANIZER, ADMIN | An organizer who could fetch a student's QR could check in on the student's behalf |
| Ownership check | `@passSecurity.ownsEvent(eventId)` compares the caller with `organizerId` from the cached `EventCatalogClient` | event-service owns the organizer; pass-service doesn't copy it |
| QR rendering | ZXing `QRCodeWriter`, error correction **H**, margin 2 modules. PNG via `BufferedImage` (our own writer, so the colours are ours), SVG built by hand as one `<path>`. Logo: a centred badge covering at most 20 % of the width (level H tolerates about 30 % loss). By default it's a drawn "MIT" monogram (Java2D), and `mitpass.passes.qr.logo` can point at a real PNG later | PROJECT.md: PNG + SVG, level H, college logo, themed colours. No binary asset needs committing |
| Colour safety | `passColor` must have a contrast ratio of at least 4.5 against white (validated in event-service, 400 otherwise). The QR's light modules are always white | Phone scanners fail on low-contrast codes |
| PDF ticket | OpenPDF, A6 page: maroon header band with the event title, date and time in campus time (Asia/Kolkata, same as the portal), venue + "Open map" link (`venue.mapUrl`), holder name with an initials circle, pass number in monospace, static QR (or the "live code only" note), small print with the pass id | PROJECT.md §P4. "Photo initials" = an initials circle (there are no photos) |
| Consumer | One durable queue `pass-service.inbox` bound to `registration.confirmed`, `registration.cancelled`, `event.state-changed`, `event.rescheduled`. One listener thread (`concurrency 1`, prefetch 20), so messages from event-service's relay are handled in publish order. The body is read as the envelope by `IdempotentConsumer` (common-messaging), not through a message converter | Ordering across types matters (confirm before cancel). Reading the bytes ourselves avoids Spring AMQP's converter type headers |
| Idempotent consumer | `processed_message (consumer, message_id)` PK. In the **same transaction** as the handler: `INSERT … ON CONFLICT DO NOTHING`; 0 rows → duplicate, skip and ack. Concurrent duplicates: PostgreSQL makes the second insert wait for the first transaction, then it sees the conflict. Belt and braces: `UNIQUE (registration_id)` on `pass` | D11 "consumers must be idempotent on `messageId`"; the business constraint catches anything the table misses |
| Reordering | A `RegistrationCancelled` for a registration with no pass yet leaves a tombstone row (`cancelled_registration`), so a late `RegistrationConfirmed` for it is skipped. `EventStateChanged` updates `event_ref` only if its `changedAt` is newer than the stored one. A `RegistrationConfirmed` for an event already CANCELLED is skipped | D11: "tolerate reordering across aggregates". Redeliveries after a failure can arrive out of order |
| Failures | Spring AMQP stateless retry: 3 attempts, 1 s → 4 s backoff, then reject without requeue → dead-letter exchange `mitpass.events.dlx` → `pass-service.inbox.dlq` (§19 Q2) | Without it, a poison message either loops forever or is dropped silently |
| Correlation | The listener puts the message's `correlationId` header into the MDC, so pass-service's logs and its own outbox rows (`PassIssued`, `PassRevoked`) carry the student's original request id | The chain register → pass is traceable (P8 tracing builds on it) |
| Saga step | `EventStateChanged(toStatus = CANCELLED)` → in one transaction, every ISSUED/ACTIVE pass of the event → REVOKED (`EVENT_CANCELLED`, trigger SYSTEM), with a revocation entry and a `PassRevoked` per pass. `COMPLETED` → EXPIRED for the valid passes | PROJECT.md §P4 saga step; choreography, no orchestrator |
| Scheduler | `PassLifecycleScheduler` (`@Scheduled`, off in tests, `Clock`): ISSUED → ACTIVE when `startsAt − code-unlock-before-start ≤ now`; ISSUED/ACTIVE → EXPIRED when `endsAt + expire-after-end ≤ now` (a fallback if `COMPLETED` never arrives). Batches by event, own transaction per event, `@Version` on `pass`, so it can't double-apply a move against a consumer | Same design as event-service's scheduler (D10). ShedLock waits for P7 |
| Issued straight to ACTIVE | A pass issued after its unlock time (late promotion, demo events starting soon) goes ISSUED → ACTIVE in the issuing transaction (two history rows) | The table stays the only source; no special case in the scanner |
| Revocation feed | `revocation (seq identity, pass_id, event_id, revoked_at, reason)`. `GET /api/revocations?since=<seq>&eventId=&limit=` returns `{items, nextSince}`. A cursor, not a timestamp | Clocks on scanners don't matter, and nothing is missed when two revocations share a millisecond |

## 5. Pass state machine

| From | Allowed next | Who / what moves it |
|---|---|---|
| (none) | ISSUED | consumer of `RegistrationConfirmed` (SYSTEM) |
| ISSUED | ACTIVE, REVOKED, EXPIRED | ACTIVE: scheduler at unlock time, or at issuance if already past it; REVOKED: organizer (owner), ADMIN, consumer (registration cancelled, event cancelled); EXPIRED: scheduler or `COMPLETED` (Q1: new edge) |
| ACTIVE | EXPIRED, REVOKED | EXPIRED: scheduler at `endsAt + expire-after-end` or `EventStateChanged(COMPLETED)`; REVOKED: as above |
| EXPIRED, REVOKED | none | terminal |

`allowedTransitions` isn't exposed: the only manual move is "revoke", shown when the pass is ISSUED or ACTIVE. Revoking an EXPIRED or REVOKED pass gives `409 illegal-transition` with `allowedStates: []`.

## 6. Data model

**pass-service, `V1__init.sql`** (`pass_db`, owner `pass_svc`):

| Table | Columns | Notes |
|---|---|---|
| `event_ref` | `event_id` bigint PK, `code` varchar(16), `title` varchar(160), `starts_at`, `ends_at`, `status` varchar(24) null, `status_changed_at` null, `last_pass_seq` int default 0, `updated_at` | Upserted from every message that carries event data |
| `pass` | `id` uuid PK, `pass_number` varchar(32) **unique**, `registration_id` uuid **unique**, `event_id` FK `event_ref`, `user_id` uuid, `holder_name` varchar(120), `holder_email` varchar(254), `status` varchar(8) CHECK in (ISSUED, ACTIVE, EXPIRED, REVOKED), `issued_at`, `activated_at`, `expired_at`, `revoked_at`, `revoked_by` varchar(64), `revoke_reason` varchar(24) CHECK in (ORGANIZER, REGISTRATION_CANCELLED, EVENT_CANCELLED, ADMIN), `revoke_note` varchar(200), `version`, audit columns | Indexes `(user_id, issued_at)`, `(event_id, status)` |
| `pass_history` | same shape as `event_state_history` (P3), FK `pass_id` | |
| `revocation` | `seq` bigint identity PK, `pass_id` FK, `event_id`, `revoked_at`, `reason` | Index `(event_id, seq)` |
| `cancelled_registration` | `registration_id` uuid PK, `cancelled_at` | Tombstones for reordering (§4) |
| `signing_key` | as auth-service's, plus `max_token_exp` timestamptz null; `algorithm` = ES256 | Partial unique index: one ACTIVE |
| `processed_message` | `consumer` varchar(60), `message_id` uuid, `processed_at`; PK both | From common-messaging's new reference DDL; cleaned after 30 days |
| `outbox_message` | reference DDL (D11) | |

**event-service, `V7__pass_settings.sql`:** `event.static_qr_allowed boolean NOT NULL DEFAULT true`, `event.pass_color varchar(7) NULL` CHECK `^#[0-9A-F]{6}$`. DTOs gain `staticQrAllowed`, `passColor` (create, update, detail; fields only added). The seed gives TECHFEST26 a blue and RANG26 a purple that pass the contrast check, and switches static QR off for one event (AIWS26) for exit criterion 5.

## 7. REST API (pass-service; same paths through the gateway)

| Method | Path | Who | Success | Errors |
|---|---|---|---|---|
| GET | `/api/passes/me?status=&page=` | authenticated | 200 `PageResponse<PassResponse>` | — |
| GET | `/api/passes/{id}` | holder, event owner, ADMIN | 200 `PassResponse {id, passNumber, status, eventId, eventCode, eventTitle, startsAt, endsAt, holderName, issuedAt, codeUnlocksAt, staticQrAllowed, revokedAt?, revokeReason?}` | 404 |
| GET | `/api/passes/{id}/history` | holder, event owner, ADMIN | 200 `[PassChangeResponse]` | 404 |
| GET | `/api/passes/{id}/qr/live` | holder | `Accept: image/png` (default) → PNG; `image/svg+xml` → SVG; `application/json` → `{token, expiresAt, refreshAfterSeconds}`. `Cache-Control: no-store` | 404, 409 `code-locked` (+ `unlocksAt`), 409 `pass-not-valid` |
| GET | `/api/passes/{id}/qr/static` | holder | same three media types, long-lived token | 404, 409 `static-qr-disabled`, 409 `pass-not-valid` |
| GET | `/api/passes/{id}/ticket.pdf` | holder | 200 `application/pdf`, `Content-Disposition: attachment; filename="<passNumber>.pdf"` | 404, 409 `pass-not-valid` |
| GET | `/api/passes?eventId=&status=&page=` | event owner, ADMIN (`eventId` required) | 200 `PageResponse<PassResponse>` | 400 (no eventId), 403 |
| POST | `/api/passes/{id}/revoke` `{note?}` | event owner, ADMIN | 200 `PassResponse` (REVOKED) | 404, 409 `illegal-transition` |
| GET | `/api/revocations?since=0&eventId=&limit=500` | VOLUNTEER, ORGANIZER, ADMIN | 200 `{items: [{passId, eventId, revokedAt, reason, seq}], nextSince}` | 400 |
| GET | `/.well-known/pass-jwks.json` | public | 200 JWK set (`Cache-Control: max-age=300`) | — |
| POST | `/api/passes/keys/rotate` | ADMIN | 200 `{kid}` | — |

**New problem types:** `code-locked` 409, `static-qr-disabled` 409, `pass-not-valid` 409, `event-service-unavailable` 503 (the catalogue call failed and nothing is cached).

## 8. Message handling, step by step

Each handler runs inside `IdempotentConsumer.handle(message, "pass-service", PayloadType.class, handler)`: one transaction with the `processed_message` insert, the business change, history rows and outbox rows.

- **`registration.confirmed`** → upsert `event_ref` (code, title, times) → skip if a tombstone exists or the event is CANCELLED → `PassIssuer.issue`: next sequence, `PassNumber.of(code, seq)`, insert ISSUED (+ history, SYSTEM) → ACTIVE right away if past the unlock time → `PassIssued` outbox row.
- **`registration.cancelled`** with `previousStatus = CONFIRMED` → pass for the registration: ISSUED/ACTIVE → REVOKED (`REGISTRATION_CANCELLED`) + revocation entry + `PassRevoked`. No pass yet → tombstone. `previousStatus = WAITLISTED` → nothing to do (no pass existed).
- **`event.state-changed`** → update `event_ref` if newer → `CANCELLED`: revoke all valid passes (saga step) → `COMPLETED`: expire all valid passes → `LIVE`: activate any still ISSUED (catch-up) → others: status only.
- **`event.rescheduled`** → update `starts_at` / `ends_at` if newer. The scheduler picks up the new unlock and expiry times on its next run.

**New contracts** (records in `common-messaging`, fields only ever added):

- `PassIssued {passId, passNumber, registrationId, eventId, eventCode, eventTitle, userId, holderName, holderEmail, issuedAt, eventStartsAt}` (`pass.issued`)
- `PassRevoked {passId, passNumber, registrationId, eventId, userId, reason, revokedAt, revokedBy}` (`pass.revoked`)
- `EventRescheduled {eventId, eventCode, startsAt, endsAt, changedAt, changedBy}` (`event.rescheduled`), published by event-service from `EventService.update` when either time changes and the event isn't DRAFT

## 9. `libs/common-messaging` and `libs/common-security` additions

**common-messaging (consumer side, announced in phase-3 §9):**

- `contract/EventEnvelope<T>` record (the fields the publisher writes) + `EnvelopeReader` (bytes → envelope with a typed payload, Jackson 3 `JsonMapper`, unknown fields ignored).
- `IdempotentConsumer` (auto-configured when a `DataSource` and transaction manager exist): `handle(Message, consumerName, Class<T>, Consumer<EventEnvelope<T>>)`. It sets the MDC (`correlationId`, `messageId`), opens a transaction, runs `INSERT INTO processed_message … ON CONFLICT DO NOTHING`, then runs the handler or logs the duplicate and returns.
- `mitpass/messaging/processed-message-postgresql.sql` reference DDL, run by the lib's tests like the outbox DDL. Cleanup of rows older than `mitpass.messaging.consumer.retention` (30 d) on the relay's thread.
- `DeadLetterTopology`: the `mitpass.events.dlx` direct exchange. `InboxQueues.durableWithDeadLetter(name)` builds a queue with `x-dead-letter-exchange` / `x-dead-letter-routing-key` and its `.dlq` partner, so P5 and P7 consumers declare theirs the same way.
- `PassIssued`, `PassRevoked`, `EventRescheduled` contracts.

**common-security:** `com.mitpass.common.security.keys.KeyEncryptor(String secret)`. It's a plain class: each service creates the bean from its own property. auth-service's copy is deleted, and its `KeyEncryptor` bean comes from `mitpass.auth.key-secret` as before. A compatibility test decrypts a value produced by the old class.

## 10. Portal slice (`web-portal`)

D9 rules as before: tokens only in the session, CSRF on, PRG, all calls through the gateway.

| Page | Path | Who | Backend calls |
|---|---|---|---|
| My passes | `GET /my/passes` | logged in | `GET /api/passes/me` |
| Pass page (screens 3–4, thin) | `GET /my/passes/{id}` | holder | `GET /api/passes/{id}`. Card: event, date, venue, holder, pass number (monospace), state. QR `<img src="/my/passes/{id}/qr.png">` reloaded every 30 s by a 6-line script (cache-busting query). Locked → "Code unlocks at …"; REVOKED/EXPIRED → state banner, no QR. "Download PDF ticket" link |
| QR image proxy | `GET /my/passes/{id}/qr.png` | holder | `GET /api/passes/{id}/qr/live` (`Accept: image/png`) → bytes with `Cache-Control: no-store`; 409 → a small placeholder PNG |
| PDF proxy | `GET /my/passes/{id}/ticket.pdf` | holder | `GET /api/passes/{id}/ticket.pdf` → streamed with the same `Content-Disposition` |
| Event registrations (organizer) | `GET /organizer/events/{id}/registrations` | ORGANIZER (owner), ADMIN | `GET /api/events/{id}/registrations`, `GET /api/passes?eventId=` (merged by registration id): name, status, waitlist #, pass number, pass state, a **Revoke** button for ISSUED/ACTIVE passes |
| Revoke | `POST /organizer/passes/{id}/revoke` (`note`, `eventId`) | ORGANIZER, ADMIN | `POST /api/passes/{id}/revoke` → redirect back with a flash |

- After a CONFIRMED registration, the event page's flash adds "Your pass is being issued: see My passes". The pass appears about 1 s later, and a reload shows it (no polling: D9).
- "My events" gains a "Registrations" link per event. The header gains "My passes".
- `GatewayClient` gains `getBytes(path, accept)` for the two proxies. Images and PDFs are proxied byte for byte; the token never leaves the portal.
- `PortalPagesTest` cases: anonymous → login redirect on `/my/passes/**`, STUDENT → 403 on the organizer pages, no JWS in the rendered pass page (regex `eyJ[\w-]+\.eyJ`), CSRF on the revoke form.
- The D9 shortcut "Live QR is an `<img>` reloaded by a plain 30 s timer" is already listed for P4. **New shortcut, added to D9 at T06.13 if it survives:** "the pass appears in My passes on reload after registering" (P6: HTMX poll on the event page; P7: push).

## 11. Gateway and other services

- Gateway routes: `pass-service` with `Path=/api/passes/**,/api/revocations/**,/.well-known/pass-jwks.json`; `pass-service-openapi` `/docs/pass-service`; `springdoc.swagger-ui.urls` gains pass-service; public path `GET /.well-known/pass-jwks.json`. `EdgeSecurityTest`: the JWKS without a token → routed (not 401); `GET /api/passes/me` without a token → 401 at the edge.
- event-service: V7, DTO fields, contrast validator, `EventRescheduled`, seed colours. Its `SecurityMatrixTest` doesn't change (no new endpoints).
- auth-service: `KeyEncryptor` import changes. Nothing else.
- CORS unchanged.

## 12. Configuration additions

```yaml
# pass-service
server.port: 8083
spring:
  datasource.url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/pass_db   # pass_svc / pass_svc
  rabbitmq: { host: ${RABBITMQ_HOST:localhost}, username: ..., password: ..., publisher-confirm-type: simple,
              listener.simple: { concurrency: 1, prefetch: 20, default-requeue-rejected: false,
                                 retry: { enabled: true, max-attempts: 3, initial-interval: 1s, multiplier: 4 } } }
mitpass:
  security.public-paths: [GET /.well-known/pass-jwks.json]
  messaging:
    tap-queue.enabled: false            # event-service already declares it
    consumer: { retention: 30d, dead-letter-exchange: mitpass.events.dlx }
  passes:
    issuer: mitpass-pass
    key-secret: ${MITPASS_KEY_SECRET:dev-only-change-me}
    live-token-ttl: 45s
    live-refresh-interval: 30s          # returned as refreshAfterSeconds; the portal uses it
    static-valid-after-end: 2h
    code-unlock-before-start: 150m      # 2 h before doors open (doors open 30 min before start)
    expire-after-end: 2h
    scheduler: { enabled: true, interval: 30s }
    event-cache-ttl: 60s
    qr: { size: 400, margin: 2, logo: "", logo-ratio: 0.2, default-color: "#7A1F2B" }
    ticket: { zone: Asia/Kolkata }
```

Tests: scheduler off, relay off unless a test checks messaging, listener container `auto-startup: false` except in the consumer tests, a fixed `Clock`.

## 13. Ports and running locally

| Port | What |
|---|---|
| 8083 | **pass-service** (new) |
| 8080 / 8081 / 8082 / 8090 / 8761 / 8025 / 15672 | gateway / auth / event / portal / Eureka / Mailpit / RabbitMQ UI (unchanged) |

Start order: discovery → auth → event → **pass** → gateway → portal. pass-service declares its queue on startup, so start it before registering anyone: messages published before the queue exists go only to the tap queue. For a clean demo run `docker compose down -v` first. Registrations kept from the P3 demo have no passes, and P4 doesn't backfill them (a backfill would need event-service to replay; not worth it for dev data). Demo events with seed dates in Nov 2026 – Jan 2027 stay ISSUED (code locked) until 2.5 h before they start. The exit demo creates its own event starting about 2 h ahead, so its passes are ACTIVE at once; `docs/http/phase-4.http` does the same.

## 14. `scripts/VerifyPassToken.java`

A single-file Java program run with the project JDK: `.tools/jdk-21/bin/java scripts/VerifyPassToken.java <token> [jwks-url]`. It uses only the JDK: it fetches the JWK set (`HttpClient`), picks the key by `kid`, builds the P-256 public key from `x`/`y`, and verifies with `SHA256withECDSAinP1363Format` (JWS uses raw R‖S). It prints the header, claims, expiry in campus time, and `VALID` or `INVALID SIGNATURE` / `EXPIRED` / `UNKNOWN KID`. Independent of pass-service's code, it shows the signature is standard ES256. A test runs it against a token from `PassTokenService`.

## 15. Tests

| Test | Kind | Proves |
|---|---|---|
| `KeyEncryptorTest` (common-security) | unit | round trip; a value encrypted by the old auth-service class decrypts; a wrong secret fails |
| `EnvelopeReaderTest`, `IdempotentConsumerTest` (common-messaging) | unit + Testcontainers PostgreSQL | typed payload, unknown fields ignored; first delivery runs the handler, a second is skipped; a handler exception rolls back the `processed_message` row (so a retry runs again); two threads with one message id → handler runs once; reference DDL runs |
| `DeadLetterTopologyTest` (common-messaging) | Testcontainers RabbitMQ | a rejected message lands in the `.dlq` |
| `PassStatusTest`, `PassNumberTest` | unit | every allowed move, every illegal pair with `allowedStates`; Luhn with ISIN test vectors (`US0378331005`), every single-character typo and most adjacent swaps detected, format and parsing |
| `PassTokenServiceTest` | unit | sign/verify round trip; one changed character in header, payload or signature → fails; wrong key → fails; claims and `exp` for live and static; `max_token_exp` raised |
| `PassKeyServiceTest` | Testcontainers | first key on demand; rotation keeps the old key published until `max_token_exp + 5 min`; private key encrypted in the table |
| `QrRendererTest` | unit | PNG and SVG decode back to the same text with ZXing's reader **with the logo in place**; colours applied; level H |
| `TicketPdfRendererTest` | unit | the PDF opens, has one page, and contains the title, pass number and holder (OpenPDF text extraction); no QR when static is disabled |
| `PassIssuerTest`, `PassLifecycleServiceTest` | `@SpringBootTest` + Testcontainers | issue → number, history, outbox row; immediate ACTIVE after the unlock time; revoke (each reason) → revocation entry + `PassRevoked`; illegal revoke → 409; saga revokes all valid passes of the event and nothing else; COMPLETED expires them |
| `PassLifecycleSchedulerTest` | Testcontainers, fixed `Clock` | unlock and expiry happen once; a pass revoked meanwhile is skipped |
| **`ConsumerIdempotencyTest`** | Testcontainers PostgreSQL + RabbitMQ | the same `RegistrationConfirmed` (same `messageId`) sent twice, and twice concurrently → exactly one pass, two `processed_message` lookups, one `PassIssued`; reordering: cancel before confirm → no pass; state change older than the stored one ignored |
| `PassFlowTest` (pass-service) | Testcontainers PostgreSQL + RabbitMQ | envelope from the real event-service format → pass within 1 s, correlation id carried into `PassIssued`; poison message → DLQ after 3 attempts |
| `PassControllerTest`, `PassMediaControllerTest`, `RevocationControllerTest` | `@WebMvcTest` | status codes, problem types, media types by `Accept`, `Content-Disposition`, `no-store`, cursor paging |
| `SecurityMatrixTest` (pass-service) | `security-test-support` harness | every §7 row × anonymous, student (holder), student (other), volunteer, organizer (owner), organizer (other), admin; `EventCatalogClient` faked |
| `EventServiceTest` additions (event-service) | existing | V7 fields saved and returned; low-contrast colour → 400; `EventRescheduled` outbox row only when a time changes |
| `VerifyPassTokenScriptTest` | unit | the script verifies a valid token and rejects a tampered one |
| Portal: `PortalPagesTest` additions, `GatewayClientTest` (`getBytes`) | existing harnesses | §10 |

## 16. Documentation to update at T06.13

`README.md` (pass-service in the start order and ports, the demo journey, the verify script), `PROJECT.md` (§P4 ticks + status; §3 Pass row and services table if the owner agrees to Q1/Q3; §P4 DLQ bullet if Q2), `docs/DECISIONS.md` (D1 pins; new **D12 pass tokens and keys**: claims, ES256, rotation rule, live/static lifetimes; **D13 consumers**: inbox queue, idempotency, reordering, DLQ; D9 new slice row if any), `docs/security-matrix.md`, `docs/http/phase-4.http`, AGENTS.md "Parts to keep in mind" (common-messaging consumer side), "As built" here, `log.md`.

## 17. Steps (log.md T06.1–T06.13)

Each step ends with `./mvnw -pl <module> -am verify` green, a full `./mvnw verify` after any step that touches `libs/`, and a `log.md` entry.

| # | Step | Size | Done when |
|---|---|---|---|
| T06.1 | **Owner decisions recorded:** answers to §19 in PROJECT.md (§3 Pass row, services table, §P4) and DECISIONS (D12/D13 drafts), before any code | S | owner's answers written down; this plan's §2 matches them |
| T06.2 | **common-security `KeyEncryptor`** moved; auth-service uses it | S | `KeyEncryptorTest` green, auth-service suite green (keys still decrypt) |
| T06.3 | **common-messaging consumer side:** envelope reader, `IdempotentConsumer`, processed-message DDL + cleanup, dead-letter topology, 3 new contracts | M | `EnvelopeReaderTest`, `IdempotentConsumerTest`, `DeadLetterTopologyTest` green; full suite green |
| T06.4 | **event-service:** V7, DTO fields, contrast validation, `EventRescheduled`, seed | S | event-service tests green, incl. the new cases |
| T06.5 | **pass-service skeleton:** module, POM pins, app, config, V1, Testcontainers base, Eureka, gateway routes + Swagger + public path, `EdgeSecurityTest` cases | M | context test green; live: the service registers in Eureka and its Swagger page shows at :8080 |
| T06.6 | **Domain:** `PassStatus`, `PassNumber`, `Pass`, history, `EventRef` | S | `PassStatusTest`, `PassNumberTest` green |
| T06.7 | **Keys and tokens:** `PassKeyService` (ES256, encrypted, rotation, `max_token_exp`), JWKS endpoint, `PassTokenService`, `scripts/VerifyPassToken.java` | M | `PassKeyServiceTest`, `PassTokenServiceTest`, `VerifyPassTokenScriptTest` green |
| T06.8 | **Consumers:** inbox queue + DLQ, listener, issue / revoke / saga / expire / reschedule handlers, tombstones, `PassIssued` / `PassRevoked` outbox | L | `PassIssuerTest`, `PassLifecycleServiceTest`, `ConsumerIdempotencyTest`, `PassFlowTest` green |
| T06.9 | **Scheduler:** unlock and expiry | S | `PassLifecycleSchedulerTest` green |
| T06.10 | **QR + media endpoints:** `QrRenderer`, `EventCatalogClient` (cache), live/static endpoints by `Accept`, problem types | M | `QrRendererTest`, `PassMediaControllerTest` green |
| T06.11 | **PDF ticket** | M | `TicketPdfRendererTest` green; the ticket opened and checked by eye once |
| T06.12 | **Read + revoke API, revocation feed, key rotation endpoint, security matrix**, `docs/security-matrix.md` | M | `PassControllerTest`, `RevocationControllerTest` green; every matrix cell green |
| T06.13 | **Portal slice + exit demo + docs:** pages of §10, `phase-4.http`, README, PROJECT ticks, DECISIONS D12/D13, As built, log | M | all twelve exit criteria pass; nothing left running |

Order: T06.1 → T06.2 → T06.3 → T06.4 → T06.5 → T06.6 → T06.7 → T06.8 → T06.9 → T06.10 → T06.11 → T06.12 → T06.13. T06.4 can run in parallel with T06.2–T06.3, and T06.6–T06.7 with T06.3.

## 18. Gotchas to check at build time

1. **OpenPDF 3.x packages:** the 3.0 line moved from `com.lowagie.text` to `org.openpdf.text` (check the jar; most tutorials show the old name). It needs Java 17+ (fine).
2. **ZXing without `javase`:** `MatrixToImageWriter` lives in `javase`. Write the `BitMatrix` → `BufferedImage` loop ourselves (a few lines). For decoding in tests, `BufferedImageLuminanceSource` is also in `javase`, so add `javase` with **test scope only**, excluding `jcommander` and `jai-imageio-core`.
3. **Headless AWT:** Spring Boot sets `java.awt.headless=true`. Unit tests outside a Spring context must set it too (Surefire `argLine` or a static block), or Java2D may try to open a display on CI.
4. **ES256 signatures in JWS** are raw R‖S (64 bytes), not DER. Nimbus handles it; the JDK verify script needs `SHA256withECDSAinP1363Format`.
5. **Nimbus EC keys:** `ECKeyGenerator(Curve.P_256).keyID(kid).algorithm(JWSAlgorithm.ES256)`; `ECKey.toPublicJWK()` for the set. Nimbus comes with `spring-security-oauth2-jose` through common-security; don't pin it separately.
6. **Two kinds of JWT in one service:** pass-service *verifies* RS256 user tokens (common-security, auth's JWKS) and *signs* ES256 pass tokens. Keep the pass signer away from Spring's `JwtEncoder`/`JwtDecoder` beans (use Nimbus `JWSObject` directly), so auto-configuration doesn't pick the wrong key.
7. **`@RabbitListener` + transactions:** the handler's transaction is the JPA one opened by `IdempotentConsumer` (`TransactionTemplate`), not a RabbitMQ channel transaction. A rollback → exception → retry → DLQ. Don't set `channelTransacted`.
8. **Retry and `IdempotentConsumer`:** a failed attempt must roll back the `processed_message` insert, or the retry would skip the work. Covered by `IdempotentConsumerTest`.
9. **Testcontainers 2 RabbitMQ class:** `org.testcontainers.rabbitmq.RabbitMQContainer` (as used in P3's tests).
10. **Jackson 3 and `Instant`s in the envelope:** the publisher writes them as ISO strings; `EnvelopeReader` must use a `JsonMapper` with the same settings (the app's bean), and `FAIL_ON_UNKNOWN_PROPERTIES` off for forward compatibility.
11. **Partial unique index on `signing_key`:** same as auth (phase-2 §16); a concurrent first-key creation is resolved by re-reading.
12. **Load-balanced client for `EventCatalogClient`:** `@Bean(defaultCandidate = false)` + `@LoadBalanced` (log.md note), or Eureka's own client picks it up and the service never registers.
13. **Binary responses through the gateway and portal:** Spring Cloud Gateway passes bytes through. In the portal, read with `RestClient…body(byte[].class)` and copy `Content-Type`/`Content-Disposition`; never buffer a PDF as a `String`.
14. **`Accept`-based negotiation** with `produces = {IMAGE_PNG, "image/svg+xml", APPLICATION_JSON}` on one mapping. Errors from these endpoints must still be `application/problem+json`; check that a 409 on a PNG request isn't turned into a 406.
15. **Contrast check:** relative luminance per WCAG (sRGB linearization); `#7A1F2B` vs white ≈ 10:1, so the default passes.
16. **Repeatable seed + V7 defaults:** `static_qr_allowed` has a default, so `R__demo_seed.sql` keeps working; the colour updates must be idempotent `UPDATE`s.
17. **Windows editing:** Python writes cp1252 and CRLF by default here (log.md); use `encoding="utf-8", newline=""`, or the Write tool.

## 19. Owner questions (resolved 2026-10-16)

1. **Q1: When does a pass become ACTIVE?** When the live code unlocks, 2 h before doors open (mockup wallet). ISSUED → EXPIRED added. PROJECT.md §3 Pass row updated; D12.
2. **Q2: Dead-letter queue now?** Yes, minimal, **and clearly marked as completed in P7**. PROJECT.md §P4 and §P7 bullets; D13.
3. **Q3: `EventRescheduled`?** Yes, a new event from event-service. PROJECT.md §3 services table and §P4; D13.
4. **Pre-phase commit:** the owner commits the planning docs and says when to start building (T06.2).

## 20. As built: deviations and findings (2026-10-16)

Phase 4 was built as planned, with the owner's answers (§19). Where the build differs from the sections above, this list is authoritative.

| Topic | Plan said | As built / why |
|---|---|---|
| Check digit | "a typo anywhere, letters included, is caught" | **Corrected (found by `PassNumberTest`):** every digit typo is caught, letter typos about 9 in 10 (T→A in TECHFEST26 slipped through). A letter expands to two digits, and no single decimal check digit can catch every substitution among 36 symbols. Behaviour unchanged; D12 and §4 corrected; P5 also matches the code part against the event |
| JWK set order | newest first | ACTIVE key first, then newest. **Bug found by `PassKeyServiceTest`:** two keys created in the same instant came back in random order |
| Token times | — | Truncated to whole seconds before signing, so `exp − iat` is exactly 45 s and the reported times match the token |
| Live code on time | scheduler only | `activateIfDue` also activates an ISSUED pass on the holder's request once the unlock time has passed, so the code never waits for the next scheduler run |
| Scheduler bean | conditional | Always present (tests call `runOnce`); only `@EnableScheduling` depends on `mitpass.passes.scheduler.enabled` (same as event-service) |
| Mapping | MapStruct | Records with `from(...)`: a handful of read models, no update mappings |
| `PassResponse` | `staticQrAllowed` | Left out: it needs a catalogue call per pass in lists. The static endpoint answers `409 static-qr-disabled` instead |
| OpenPDF | 3.0.5 | Packages `org.openpdf.text.*` (gotcha 1 confirmed). `brotli4j` (a per-OS native jar) excluded; tickets don't need it |
| ZXing | `core` + test `javase` | `javase`'s jcommander is now `org.jcommander:jcommander` (exclusion updated) |
| Accept negotiation | `produces` per format | One mapping without `produces`, format from `?format=` or `Accept` by quality; a 409 on an image request is still `problem+json` (tested). Spring 7 removed `MediaType.sortByQualityValue`, so a stable sort on the quality value |
| Event upserts | entity | `EventRefStore` with SQL upserts (`ON CONFLICT`), `GREATEST` on the "observed at" columns, `UPDATE … RETURNING` for the pass sequence; the `EventRef` entity is read-only |
| Organizer pass page | — | `GET /organizer/events/{id}/registrations` joins event-service's registrations with pass-service's passes in the portal (by registration id); event-service's `RegistrationResponse` already carried name and email |
| Eureka | "lists the six apps" | Five clients (gateway, auth, event, pass, portal); the discovery server is the sixth running app |

**Verified live** (2026-10-16, compose infra with the Phase 3 volume kept, jars on the project JDK): a scripted run of **47 checks, all passing on the first run**, covering exit criteria 1–11 (script and its QR decoder in the session scratchpad; the same steps are in `docs/http/phase-4.http`). Highlights: the pass existed 0.81 s after the registration; the live QR PNG decoded with ZXing and verified with `scripts/VerifyPassToken.java` against the gateway's `/.well-known/pass-jwks.json`, and one edited claim gave `INVALID SIGNATURE`; a `RegistrationConfirmed` republished through the RabbitMQ management API with the same `message_id` left one pass and a "Skipping duplicate" log line; cancelling a 1-seat event's registration revoked that pass and issued the promoted student's; cancelling an event revoked both its passes (`EVENT_CANCELLED`, trigger SYSTEM); after a key rotation a static token signed before it still verified; the portal journey (wallet, pass page without any token, QR and PDF pass-through, organizer revoke with PRG, CSRF 403, HttpOnly cookie). Criterion 12: `./mvnw verify` green locally (378 tests); CI runs on the next push.

**Tests:** common-web 27, common-security 13, security-test-support 15, common-messaging 22, discovery 1, gateway 19, auth-service 51, event-service 128, pass-service 58, web-portal 44. Security matrix 330/330 (auth 85, event 168, pass 77).

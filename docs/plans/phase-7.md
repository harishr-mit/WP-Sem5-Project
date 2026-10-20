# Phase 7 plan: Real-time Dashboard, Notifications & Offline Scanning

> **Source:** `PROJECT.md` §P7, `docs/DECISIONS.md` D7, D11, D13 (the DLQ is completed here), D14, D15; `docs/REVIEW-LATER.md` RL4; the UI reference screen 7 (PROJECT.md §2). **Tracked in:** `log.md` as T09, sub-tasks T09.1–T09.12 (same numbering as the steps in §15).
> **Written:** 2026-10-20, on branch `phase7` (from `main` after PR #7). This plan is a guide: if a step turns out simpler or different in practice, do the sensible thing and record the deviation in `log.md` and in a closing "As built" section here.
> **Status:** Q1–Q12 answered by the owner on 2026-10-20 (§17) and recorded in PROJECT.md §3/§P7 and `docs/DECISIONS.md` D13/D16 (T09.1, done with this plan). The owner commits the plan before any code is written.

## 1. Goal and exit criteria

**Goal:** the visible "wow" layer on top of the core flow. The organizer's attendance page (screen 7) updates live over WebSocket/STOMP. A new `notification-service` sends real emails (to Mailpit) for every step a student cares about, with the QR code and a calendar invite, plus reminders. Dead-lettered messages can be seen, replayed and alerted on. The volunteer's scanner keeps working with no network and syncs when the network returns.

**Exit demo** (all must pass before T09 is marked done):

1. **Live dashboard:** the organizer's dashboard is projected on the laptop while phone B scans. Each accepted scan moves the counter, the arrivals-per-5-min chart, the per-gate bars, the capacity gauge and the recent-scans ticker **within 1 s, with no reload**. A refused scan shows in the ticker in red/amber. A second browser on the same dashboard updates too. A student or an unassigned user can't subscribe (the server refuses the subscription, test plus a manual try).
2. **Emails in Mailpit:** register → a "You're in" email with the **inline QR** and an **`.ics`** attachment that opens in a calendar app. Register on a full event → a "You're on the waitlist (#n)" email; a cancellation frees a seat → "You got a seat" + the pass email. Cancel → a cancellation email. Organizer cancels the event → every registrant gets a cancellation email; reschedules → an updated `.ics` (same UID, higher SEQUENCE). Reminders 24 h and 1 h before (demo: an event starting soon, or the reminder offsets set short) arrive **once**, also with **two notification-service instances running** (ShedLock + the unique log row).
3. **Add to calendar:** the event and pass pages have an "Add to calendar" button that downloads the `.ics`.
4. **Dead letters:** a poison message (published by hand) lands in `notification-service.inbox.dlq` after 3 attempts. The admin health overview shows that service **amber (WARNING)**. Admin · Dead letters lists it, with its type, error and attempts. **Replay** (after fixing the cause, for example Mailpit started again) processes it once; replaying again is skipped by the idempotent consumer. **Discard** removes it. All three consumers (pass-, checkin-, notification-service) show up there.
5. **Offline scanner:** on phone B (HTTPS `lan` profile), open the scanner online once, then turn Wi-Fi off. The badge shows **OFFLINE**. Scans still give green/red results, checked in the browser (signature with WebCrypto, expiry, event, revocation list, already scanned on this phone). A pass revoked **before** going offline is refused offline. The badge counts "OFFLINE · 3 queued". Wi-Fi on → the queue syncs within 15 s and the results come back. A pass that another phone checked in meanwhile is flagged **DUPLICATE_OFFLINE**. The live dashboard counts the synced scans.
6. **Security:** every new endpoint has its rows in the service's `SecurityMatrixTest` and in `docs/security-matrix.md`. No page or offline bundle contains a JWT (the portal's `NoTokensInPagesTest` covers the new pages and the bundle). The gateway still refuses SERVICE tokens.
7. `./mvnw verify` green locally and in GitHub Actions; Playwright checks (AGENTS.md) for the dashboard, the Add-to-calendar button, the dead-letters page and the offline scanner (`context.setOffline(true)`).

## 2. Starting point and scope

**Built before P7:** checkin-service records attendance in one transaction and writes `AttendanceMarked` through the outbox. The portal's attendance page shows the summary with a Refresh button (P6). pass-service and checkin-service each have an inbox with a DLQ (`InboxQueues.inbox`, D13), but nothing to inspect, replay or alert on. auth-service sends the verification email itself (Spring Mail → Mailpit). `notification_db` and its role already exist (`infra/postgres/init.sql`), so **no `docker compose down -v`**. The scanner (`scanner.js`) works online, and it already keeps a device id in `localStorage`.

**Owner decisions for this phase** (answers to §17, 2026-10-20; recorded in PROJECT.md and D13/D16):

| Topic | Decision |
|---|---|
| Live push (Q1) | **The portal relays.** checkin-service runs STOMP at `/ws` behind the gateway. The portal's server connects as the organizer (their access token, from `TokenRefresher`) and relays the updates to the browser over the portal's own STOMP endpoint, authenticated by the HTTP session. No token reaches the browser (BFF rule kept) |
| Offline conflicts (Q2) | **First synced wins.** A check-in that's already recorded stays; every later offline scan of that pass gets `DUPLICATE_OFFLINE`. Within one batch, scans are applied in `scannedAt` order. Attendance rows are never rewritten |
| Offline modes (Q3) | **Check-in only** while offline (QR and typed pass number). Check-out is turned off offline |
| Emails (Q4) | Pass issued (inline QR + `.ics`), waitlisted, promoted, registration cancelled, event cancelled, event rescheduled (updated `.ics`), reminders 24 h and 1 h. The verification email stays in auth-service |
| Recipients (Q5) | **Ask event-service.** `RegistrationCancelled` gains `userEmail` + `userName` (added fields). Bulk emails read the registrations from event-service at send time with notification-service's SERVICE identity. No recipient copy, so no reconciler |
| DLQ tooling (Q6) | **Admin portal page** (Admin · Dead letters) over ADMIN endpoints that `common-messaging` adds to every consumer: list, replay, discard. A non-empty DLQ turns that service **WARNING** (amber) on the health overview, plus a metric for P8's Grafana |
| Dashboard access (Q7) | The event's organizer and admins. The ticker shows **first name**, gate, time and the result colour. Volunteers see their own gate's count on the scanner |
| `.ics` (Q8) | **notification-service** builds it (iCal4j): attached to emails and served publicly at `GET /api/notifications/events/{id}/calendar.ics`; an "Add to calendar" button in the portal |
| Waitlisted email (Q9) | A new domain event **`RegistrationWaitlisted`** from event-service (outbox, same transaction) |
| Sending (Q10) | **In the listener**, inside the idempotent transaction; on an SMTP failure it retries 3 times, then the message goes to the DLQ. Accepted edge: a crash between the send and the commit sends one duplicate email |
| Email language (Q11) | **English only** |
| Offline age (Q12) | A queued scan older than **12 h**, or more than 2 min in the future, is refused as `REJECTED_OFFLINE_CLOCK` (`mitpass.checkin.offline.max-age`, `max-future`) |

**Out of scope (and where it lands):**

| Not in P7 | Phase |
|---|---|
| Grafana panel for the DLQ gauge and scan rates, Zipkin, Prometheus | P8 (P7 registers the Micrometer gauges only) |
| Redis-backed gateway rate limiting | P8 (P7 adds the Redis container) |
| More than one checkin-service instance for the dashboard (the STOMP broker is in memory) | after P8 (see §4, shortcut) |
| Tamil emails, a user's saved language | not planned (Q11) |
| Undoing a check-in, admin overrides | after P8 (RL10, RL12) |

## 3. Module layout after Phase 7

```
infra/docker-compose.yml          # + redis:7-alpine (:6379)
libs/common-messaging/            # + DeadLetters (list/replay/discard), DeadLetterController (ADMIN),
                                  #   DeadLetterHealthIndicator (WARNING), gauge mitpass.dlq.messages;
                                  #   contract RegistrationWaitlisted; RegistrationCancelled + userEmail, userName
platform/api-gateway/             # + routes /api/notifications/**, /ws/checkin (lb:ws), /api/admin/dead-letters/{svc}/**;
                                  #   swagger-ui entry for notification-service; WARNING in the health overview
services/event-service/           # publishes RegistrationWaitlisted; RegistrationCancelled carries email/name;
                                  #   GET /api/events/{id}/registrations allows SERVICE
services/pass-service/            # GET /api/passes/{id}/qr/static allows SERVICE (email QR); dead-letter endpoints
services/checkin-service/         # + Redis live counters (LiveStats), STOMP /ws + LiveFeedPublisher, GET .../live,
                                  #   POST /api/scans/batch, GET /api/scans/manifest, V2 (scan_log offline columns),
                                  #   codes DUPLICATE_OFFLINE, REJECTED_OFFLINE_CLOCK; gateCount on the scan answer
services/notification-service/    # NEW (:8085): inbox + DLQ, mail templates, IcsBuilder, reminders (ShedLock),
                                  #   EventCatalogClient + RegistrationsClient (SERVICE token), V1
web-portal/
  .../live/                       # LiveFeedRelay (upstream STOMP client), WebSocketConfig (/portal-ws, session auth,
                                  #   subscribe check)
  .../web/                        # dashboard page, Add to calendar, /scan/offline/bundle, /scan/batch, admin dead letters
  static/js/dashboard.js          # STOMP client + Chart.js
  static/js/offline/*.js          # idb queue, WebCrypto verify, Luhn, sync
  static/scan-sw.js               # service worker (scope /scan)
  templates/organizer/live.html, admin/dead-letters.html
```

## 4. Key decisions for this phase

- **Counters in Redis, truth in PostgreSQL.** checkin-service's attendance tables stay the source of truth. Redis holds a cache that can be rebuilt (D14 rule): per event `checkedIn`, `inside`, per gate counts, arrivals in 5-minute buckets, and the last 20 scans (accepted and refused). It is updated **after commit** (`TransactionSynchronization.afterCommit`) and rebuilt from the database when a key is missing (first read after a restart or a flushed Redis). **Redis down never fails a scan:** the update is logged and skipped, and the live view falls back to a database query.
- **Push a whole snapshot, not deltas.** After each change the publisher sends the event's full snapshot (`LiveSnapshot`) to `/topic/events/{id}`, at most once per `mitpass.checkin.live.throttle` (500 ms) per event; changes in between are folded into the next one. The browser just redraws, so a lost or doubled frame doesn't matter. `GET /api/attendance/events/{id}/live` returns the same snapshot, for the first paint and when the socket is down.
- **The portal relays (Q1).** The browser opens `/portal-ws` (STOMP over WebSocket, same origin, session cookie, CSRF token in the CONNECT frame). On `SUBSCRIBE /topic/events/{id}` the portal checks the user may read that event's attendance (one gateway call; 403 → the subscription is refused), then `LiveFeedRelay` opens (or reuses, per session and event) an upstream STOMP session to `ws://gateway/ws/checkin` with the user's `Authorization: Bearer` on the handshake. checkin-service authenticates the handshake as a resource server and checks the same ownership on its `SUBSCRIBE` (ChannelInterceptor). Upstream sessions close when the browser's last subscription goes, and they are reopened every `mitpass.portal.live.reconnect-every` (10 min) with a fresh token, so a revoked organizer loses the feed within that time. *Shortcut: the STOMP broker is Spring's in-memory simple broker, so only one checkin-service instance can serve the dashboard; upgrade to the RabbitMQ STOMP relay if the service is ever scaled out.*
- **Volunteers get a gate count, not the dashboard (Q7).** The scan answer gains `gateCheckedIn` (from Redis), shown on the scanner as "Checked in at this gate". This is an added field, and there's no WebSocket on the scanner.
- **notification-service asks the owners (Q5).** One-recipient messages carry the email (`RegistrationConfirmed`, `PassIssued`, `WaitlistPromoted`, `RegistrationWaitlisted` (new), `RegistrationCancelled` (+ email)). Bulk mails (event cancelled, rescheduled, reminders) read `GET /api/events/{id}/registrations` (CONFIRMED, and WAITLISTED for a cancellation) with the SERVICE token, at send time. The event's title, venue and times come from the public catalogue (`GET /api/events/{id}`, 60 s cache).
- **Idempotent emails.** `notification_log` has `UNIQUE (dedup_key)`. The key is `<messageId>:<recipient>` for message mails and `reminder:<kind>:<registrationId>` for reminders. A bulk mail that fails half-way is retried, and the recipients already sent are skipped by the key. The log row commits in the same transaction as `processed_message`.
- **Reminders (ShedLock).** A `@Scheduled` job every `mitpass.notifications.reminders.interval` (1 min) under `@SchedulerLock` (JDBC provider, `shedlock` table in `notification_db`). It finds events that start within the 24 h and 1 h windows (catalogue `from`/`to`), reads their CONFIRMED registrations and sends each reminder that has no log row yet. ShedLock stops two instances running at once, and the unique key stops a duplicate if they ever overlap.
- **Email QR.** The pass email embeds the **static** QR as a CID image from `GET /api/passes/{id}/qr/static` (SERVICE now allowed). If the event doesn't allow a static QR (pass-service refuses), the email says "open your pass in MITPass" with a link to the wallet instead. Links point at `mitpass.notifications.portal-url`.
- **`.ics`.** iCal4j, `UID = event-<id>@mitpass`, `SEQUENCE` = how many times the event has been rescheduled (from the catalogue's `version`), `METHOD:PUBLISH` (`CANCEL` in the cancellation email). Times in UTC with the venue as `LOCATION`.
- **Dead letters, done (Q6, D13).** `common-messaging` registers, for each declared inbox, a `DeadLetters` bean, `GET /api/dead-letters` (peek up to 50: id, type, routing key, first death reason, attempts, the last exception text from the header, body size), `POST /api/dead-letters/{messageId}/replay` (moves it back to the inbox with the same `messageId`, so the idempotent consumer still applies it once), `POST /api/dead-letters/replay-all` and `DELETE /api/dead-letters/{messageId}`. All ADMIN. The gateway routes `/api/admin/dead-letters/<service>/**` to each consumer (its own `/api/admin/**` ADMIN rule applies too). `DeadLetterHealthIndicator` reports **WARNING** (HTTP 200, shown amber) while a DLQ holds messages, and `mitpass.dlq.messages{queue}` is a gauge.
- **Offline is a provisional check (Q2, Q3, Q12).** The browser decides green/red on its own only while offline, and every offline scan is queued and re-checked by the server on sync with the full pipeline. The server is still the authority: a scan the phone showed green can come back `DUPLICATE_OFFLINE` or `REVOKED` (revoked after the bundle was taken), and the volunteer sees the corrected result in the sync list.
- **No new pass state, no renamed fields.** New: one event type, two fields on `RegistrationCancelled`, two scan result codes, `gateCheckedIn` on the scan answer, two scan_log columns.

## 5. Live dashboard (screen 7, finished)

- **Page:** `/organizer/events/{id}/live` (the attendance page's "Live" tab; the P6 page keeps the attendee list, certificates and CSV). Tiles: **checked in / confirmed** (an animated counter), **inside now / venue capacity** (gauge), **arrivals per 5 min** (Chart.js bar chart, the event day so far), **per gate** (horizontal bars), **recent scans** (ticker of the last 20: time, first name, gate, a coloured result word; newest on top, `aria-live="polite"`).
- **First paint** is server-rendered from `GET …/live` (works without JS). `dashboard.js` connects to `/portal-ws`, subscribes, and redraws on every snapshot. If the socket drops it shows "Reconnecting…" and polls `…/live` every 5 s until the socket is back.
- **Projector mode:** a "Full screen" button (Fullscreen API, larger type). Dark and light themes as the rest of the portal.
- **`LiveSnapshot`:** `{eventId, at, confirmed, checkedIn, inside, capacity, gates[{gateId, code, checkedIn}], arrivals[{bucketStart, count}], recent[{at, firstName, gateCode, code, color}]}`. The ticker carries first names only (Q7).

## 6. Notifications

| Trigger (routing key) | Email | Recipients | Extras |
|---|---|---|---|
| `pass.issued` | "Your pass for <event>" | the holder (`holderEmail`) | inline static QR (or a wallet link), `.ics` |
| `registration.waitlisted` (new) | "You're on the waitlist (#n)" | the student | — |
| `registration.waitlist-promoted` | "You got a seat" | the student (`userEmail`) | the pass email follows on its own |
| `registration.cancelled` | "Registration cancelled" | the student (new `userEmail`) | — |
| `event.state-changed` → `CANCELLED` | "<event> is cancelled" | CONFIRMED + WAITLISTED (event-service) | `.ics` with `METHOD:CANCEL` |
| `event.rescheduled` | "<event> has a new time" | CONFIRMED (event-service) | updated `.ics` |
| scheduler | "Tomorrow: <event>" / "In 1 hour: <event>" | CONFIRMED (event-service) | the 1 h reminder links to the live pass |

Templates: Thymeleaf HTML + a plain-text part, one layout in the portal's colours (inline CSS, as email clients need), sender `mitpass.notifications.from`. `UserRegistered` isn't used (auth-service sends the verification email).

## 7. Offline scanner

- **Service worker** `scan-sw.js`, scope `/scan`: caches the app shell on install (the scanner page, CSS, JS, fonts, `html5-qrcode`). Pages are network-first with the cached copy as the fallback; static files are cache-first (they are content-hashed already, D15).
- **Offline bundle:** the portal's `GET /scan/offline/bundle?eventId=` (session, volunteer for that event) collects from the gateway: the pass JWK set (public), the revocation list for the event (`/api/revocations`), the **attendee manifest** (new `GET /api/scans/manifest?eventId=`: passId, passNumber, first name, status; from checkin-service's pass copy; assigned volunteers, the organizer and admins), the gates and the session windows. No token is in it. It's stored in IndexedDB and refreshed every 60 s while online (revocations as a delta from the cursor).
- **Checking offline** (`offline/verify.js`): split the JWS; import the JWK by `kid` (`crypto.subtle.importKey('jwk', …, {name: 'ECDSA', namedCurve: 'P-256'})`); verify (`ECDSA` + `SHA-256`; JWS uses the raw `r‖s` form that WebCrypto expects); `iss`, `exp` (+ the 10 s skew), `eid` = this event, a session window open now, `pid` not revoked, `pid` in the manifest (otherwise amber `UNKNOWN_PASS`), `pid` not already accepted on this phone (`ALREADY_CHECKED_IN`). A typed pass number: Luhn (a JS port of `PassNumber`) + a manifest lookup + the visual name check. Check-out mode is off offline (Q3).
- **Queue:** every offline attempt (green or not) goes into IndexedDB as `{localId (UUID), deviceId, eventId, gateId, token | passNumber, scannedAt, localResult}`. The badge shows "OFFLINE · n queued".
- **Going offline / online:** a failed `/scan/submit` (network error or timeout `mitpass.portal.scan-timeout`, 4 s) switches to offline mode at once; `navigator.onLine` and the `online` event are hints only. While queued items exist, sync runs on `online` and every 15 s: `POST /scan/batch` (portal → gateway `POST /api/scans/batch`), up to 100 items per call. Synced items are removed only after their results come back, and the sync list shows each one, with the server's result where it differs from the phone's.
- **Session expired while offline** (portal session 30 min): the sync gets the login redirect. The queue stays in IndexedDB, and the badge says "Log in to sync". After the login the same device syncs it.

## 8. Batch sync (checkin-service)

- `POST /api/scans/batch` `{deviceId, scans: [{localId, token? , passNumber?, gateId, scannedAt}]}` → `{results: [{localId, code, message, color, passNumber?, holderName?, scannedAt}]}`. Same roles as `POST /api/scans` (VOLUNTEER; organizers at their own events, as P6).
- **Idempotent:** `scan_log` gains `local_id` and `offline` (V2), with `UNIQUE (device_id, local_id)`. A resent item returns the stored result.
- **Order and conflicts (Q2):** items are sorted by `scannedAt` and run one by one through the normal pipeline and check-in transaction, with **`scannedAt` as the clock** for expiry and the session window. The unique attendance row decides: an item whose pass is already checked in for that session (by an online scan, another phone, or an earlier item) gets **`DUPLICATE_OFFLINE`** (red; "already checked in at <time>, gate <code>"). The existing row isn't changed.
- **Clock guard (Q12):** `scannedAt` older than `offline.max-age` (12 h) or more than `offline.max-future` (2 min) ahead of the server → `REJECTED_OFFLINE_CLOCK` (amber).
- **Event state:** an offline item is accepted if the event is LIVE, or COMPLETED after `scannedAt` (the device was offline when doors closed). Otherwise the normal `EVENT_CLOSED` / `NOT_YET_OPEN`.
- **Accepted items** write the attendance, presence, scan log (`offline = true`) and `AttendanceMarked` exactly as an online scan does, so pass-service and the live dashboard follow on their own.

## 9. Dead-letter handling (completes D13, RL4)

| Piece | Where | Behaviour |
|---|---|---|
| Declaration | `InboxQueues.inbox(...)` (unchanged) | notification-service uses it too, so all three consumers have `<service>.inbox.dlq` |
| Inspect | `GET /api/dead-letters` | peek: `basicGet` without ack, then `basicNack(requeue=true)` after reading the page (the DLQ order may change, which is fine for a DLQ) |
| Replay | `POST /api/dead-letters/{messageId}/replay`, `/replay-all` | republish to the default exchange with routing key = the inbox name, same `messageId`, `type` and body; `x-death` removed; then ack the DLQ copy (publisher confirm first) |
| Discard | `DELETE /api/dead-letters/{messageId}` | ack the DLQ copy, logged at WARN with the type and id |
| Signal | `DeadLetterHealthIndicator` `deadLetters` component | `WARNING` while the DLQ isn't empty (status order `DOWN, OUT_OF_SERVICE, WARNING, UP`; HTTP 200), `mitpass.dlq.messages{queue}` gauge |
| UI | portal Admin · Dead letters | one table per service via the gateway, Replay / Replay all / Discard (dialogs, server-enforced), health page shows WARNING amber |

## 10. Backend changes outside the new service

| Where | Change | Contract note | Test |
|---|---|---|---|
| common-messaging | `RegistrationWaitlisted {registrationId, eventId, eventCode, eventTitle, userId, userName, userEmail, position, waitlistedAt}`; `RegistrationCancelled` + `userEmail`, `userName` | new type; added fields | contract round-trip test |
| event-service | publish `RegistrationWaitlisted` in the registration transaction; fill the new cancelled fields; `GET /api/events/{id}/registrations` allows SERVICE | — | `RegistrationOutboxTest`, matrix row |
| pass-service | `GET /api/passes/{id}/qr/static` allows SERVICE | — | matrix row |
| checkin-service | Redis, STOMP, live endpoint, batch, manifest, V2, two codes, `gateCheckedIn` | added field, new endpoints | §13 |
| api-gateway | routes (§3), WebSocket route with the JWT pre-check on the handshake, WARNING colour in the health payload passed through | — | `EdgeSecurityTest` rows, route test |
| web-portal | relay, dashboard, Add to calendar, offline bundle + batch, dead letters page | — | §13 |

## 11. Configuration additions

```yaml
# checkin-service
spring.data.redis: {host: ${REDIS_HOST:localhost}, port: 6379}
mitpass:
  checkin:
    live:
      throttle: 500ms          # at most one snapshot per event this often
      recent-size: 20
      bucket: 5m
    offline:
      max-age: 12h             # Q12
      max-future: 2m
      batch-max: 100

# notification-service
spring.mail: {host: ${MAIL_HOST:localhost}, port: 1025}
mitpass:
  notifications:
    from: "MITPass <no-reply@mitpass.local>"
    portal-url: http://localhost:8090
    reminders:
      enabled: true            # off in tests
      interval: 1m
      offsets: 24h,1h
      window: 5m               # an event is "due" if it starts within offset ± window/2
      lock-at-most-for: 4m
    catalog-cache-ttl: 60s
    service-client: {id: notification-service, secret: ${MITPASS_SERVICE_SECRET_NOTIFICATION:dev-only-notification-secret}}

# auth-service: mitpass.auth.service.clients += notification-service (SERVICE)

# web-portal
mitpass:
  portal:
    live:
      reconnect-every: 10m     # upstream STOMP session re-opened with a fresh token
      fallback-poll: 5s
    scan-timeout: 4s           # then the scanner goes offline
    offline:
      bundle-refresh: 60s
      sync-every: 15s
```

## 12. Ports and running locally

- New: **notification-service :8085**, **Redis :6379** (compose). Mailpit UI stays at :8025.
- No new database (notification_db exists), no `docker compose down -v`.
- Start order: discovery → auth → event → pass → checkin → notification → gateway → portal.
- The offline demo needs the `lan` profile (service workers and WebCrypto need a secure context; `localhost` counts for desktop checks).

## 13. Tests

| Test | Kind | Covers |
|---|---|---|
| `DeadLettersTest` (common-messaging) | Testcontainers RabbitMQ | poison → DLQ after 3; list; replay → applied once; replay again → skipped; discard; health WARNING ↔ UP |
| `LiveStatsTest` | Testcontainers Redis + Postgres | counters after commit only (a rolled-back scan doesn't count), rebuild after `FLUSHALL`, Redis down → scan still ACCEPTED |
| `LiveFeedTest` | STOMP client against the app | owner receives a snapshot within 1 s of a scan; student's SUBSCRIBE refused; throttle folds a burst of 20 scans into ≤ 3 frames |
| `BatchSyncTest` | slice + DB | order by `scannedAt`; resend same `localId` → same result; online-then-offline → `DUPLICATE_OFFLINE`; two devices → first synced wins; 13 h old → `REJECTED_OFFLINE_CLOCK`; expired-at-sync but valid-at-`scannedAt` token → ACCEPTED; COMPLETED after `scannedAt` → ACCEPTED |
| `ManifestTest`, matrix rows | MockMvc | roles; first names only |
| notification `MailFlowTest` | Testcontainers Postgres + GreenMail (or Mailpit container) | each trigger → the right email, subject, recipient; inline QR CID present; `.ics` parses with iCal4j; redelivered message → one email; SMTP down → retried, then DLQ |
| `ReminderJobTest` | fixed `Clock` | due windows, once per registration, two job runs in parallel → one email each (ShedLock + key) |
| `IcsBuilderTest` | unit | UID stable, SEQUENCE grows, CANCEL method |
| event-service `RegistrationOutboxTest` | DB | waitlisted event in the same transaction; cancelled carries email/name |
| portal `LiveRelayTest` | unit with a fake upstream | subscribe check, one upstream per session+event, closes on unsubscribe, reconnects with a fresh token |
| portal `NoTokensInPagesTest` | MockMvc | + live page, offline bundle JSON, dead letters page |
| portal pages | MockMvc | dashboard first paint, Add to calendar pass-through, `/scan/batch`, dead letters actions |
| JS: `offline/verify.js`, Luhn | a small test page run by Playwright | a real ES256 token from pass-service verifies; one changed character fails; Luhn same vectors as `PassNumberTest` |
| Playwright (not in `./mvnw verify`) | browser | dashboard updates on a scan, offline scanner with `setOffline(true)` → queue → sync, 360 px and dark mode for the new pages |

## 14. Documentation to update at T09.12

`README.md` (Redis, notification-service, start order, the offline and email demos), `PROJECT.md` (§P7 ticks + status), `docs/DECISIONS.md` (D13 as built, D16 as built), `docs/security-matrix.md`, `docs/http/phase-7.http` (live snapshot, batch, manifest, dead letters, `.ics`), AGENTS.md parts table (notification-service row; Redis under Infrastructure; the DLQ endpoints under Domain events; the relay under Web portal), `docs/REVIEW-LATER.md` (RL4: what P7 built), "As built" here, `log.md`.

## 15. Steps (log.md T09.1–T09.12)

Each step ends with `./mvnw -pl <module> -am verify` green, a full `./mvnw verify` after any step that touches a lib or more than one module, and a `log.md` entry.

| # | Step | Size | Done when |
|---|---|---|---|
| T09.1 | **Owner decisions recorded** (Q1–Q12) in PROJECT.md §3/§P7 and DECISIONS (D13 note, D16 draft) | S | done with this plan (2026-10-20) |
| T09.2 | **Dead letters in common-messaging:** `DeadLetters`, controller, health indicator, gauge; pass- and checkin-service adopt them; gateway routes; matrix rows | M | `DeadLettersTest` green; full verify |
| T09.3 | **Contracts + event-service + pass-service:** `RegistrationWaitlisted`, cancelled fields, SERVICE on registrations list and static QR | S | event/pass suites green; matrix rows |
| T09.4 | **notification-service skeleton:** module (:8085), V1 (`processed_message`, `notification_log`, `shedlock`), inbox + DLQ, clients with SERVICE token (auth client registered), gateway route + Swagger, `SecurityMatrixTest` | M | starts, registers in Eureka, inbox and DLQ declared; matrix green |
| T09.5 | **Emails:** templates, `IcsBuilder`, pass email with CID QR, waitlisted/promoted/cancelled, event cancelled/rescheduled via event-service | L | `MailFlowTest`, `IcsBuilderTest` green |
| T09.6 | **Reminders + `.ics` endpoint:** ShedLock job; public `calendar.ics`; portal "Add to calendar" button | M | `ReminderJobTest` green; button pass-through test |
| T09.7 | **Redis + live counters:** compose service, `LiveStats` after commit + rebuild, `GET …/live`, `gateCheckedIn` | M | `LiveStatsTest` green |
| T09.8 | **STOMP in checkin-service:** `/ws`, handshake auth, SUBSCRIBE check, throttled `LiveFeedPublisher`; gateway WebSocket route | M | `LiveFeedTest` green through the gateway |
| T09.9 | **Portal relay + dashboard page** (Chart.js, ticker, gauge, gates, fallback poll, projector mode); WebJars pinned (D1) | L | `LiveRelayTest` green; Playwright: a scan moves the counter in < 1 s |
| T09.10 | **Batch sync + manifest in checkin-service:** V2, two codes, conflict and clock rules | M | `BatchSyncTest`, `ManifestTest` green |
| T09.11 | **Offline scanner in the portal:** service worker, bundle, IndexedDB queue, WebCrypto verify, Luhn, badge, sync list | L | JS test page green; Playwright offline flow green |
| T09.12 | **Admin dead letters page + exit demo + docs** | M | all seven exit criteria pass (offline on a real phone); nothing left running |

Order: T09.1 → T09.2 → T09.3 → T09.4 → T09.5 → T09.6 → T09.7 → T09.8 → T09.9 → T09.10 → T09.11 → T09.12. The three parts are independent after T09.3, so the order of notifications (T09.4–6), live (T09.7–9) and offline (T09.10–11) can be swapped if the demo needs one first.

## 16. Gotchas to check at build time

1. **WebSocket through Spring Cloud Gateway:** the route needs the `lb:ws://` scheme, and the JWT pre-check must read the handshake's `Authorization` header (the reactive resource server sees it on the upgrade request). Check the gateway's CORS/WebSocket origin rules don't block the portal's server-side client (no `Origin` header).
2. **STOMP security in Spring Security 7:** message-level security uses `@EnableWebSocketSecurity` + an `AuthorizationManager<Message<?>>`; CSRF for STOMP is on by default, so the portal's browser client sends the CSRF token in the CONNECT headers. checkin-service's upstream endpoint is token-based and turns STOMP CSRF off.
3. **After-commit work:** Redis updates and the publish must run in `afterCommit`, never inside the transaction (a rollback would leave a wrong count). An exception there must not reach the scan's response.
4. **Redis keys per event** need a TTL (event end + 1 day) so the cache doesn't grow forever.
5. **ShedLock with JDBC** needs `usingDbTime()` so two instances with different clocks agree; the table DDL goes in V1.
6. **Email transaction:** sending inside the transaction means an SMTP timeout holds the DB connection. Set `spring.mail.properties.mail.smtp.timeout`/`connectiontimeout` (5 s).
7. **Inline images:** use `MimeMessageHelper(multipart = true)` + `addInline(cid, …)` after `setText`. Gmail-style clients block data-URI images, so CID it is.
8. **iCal4j 4.x** needs a time-zone registry; use UTC times to avoid the registry download.
9. **Service worker scope:** a worker at `/scan-sw.js` can only control `/scan` with a `Service-Worker-Allowed: /scan` header, or serve it at `/scan/sw.js`. Content-hashed URLs (D15) must be in the cache list the worker gets from the page.
10. **WebCrypto needs a secure context:** `https://<ip>:8443` with the self-signed cert works only after the phone accepted it. Service workers on a self-signed certificate: Chrome Android allows it once the warning is accepted; check on the owner's phones in the exit demo, and fall back to "IndexedDB without the worker" (the page must be open before going offline) if a browser refuses.
11. **CSRF on `/scan/batch`:** the cached page holds an old CSRF token, so sync first fetches a fresh one from a tiny `GET /scan/csrf` and retries once.
12. **Pass JWKS rotation:** a token signed by a key newer than the bundle fails offline. Refresh the bundle's JWKS on every online sync; offline, show amber "can't check this pass offline" instead of red.
13. **Live tokens on the student's phone need its network:** offline scanning is about the *scanner's* connection. The demo keeps phone A online (or uses the static QR from the PDF/email).
14. **Windows editing:** keep using the Write tool or Python with `encoding="utf-8", newline=""` (log.md).

## 17. Owner questions

**Answered 2026-10-20 (before the plan was written):**

1. **Q1: Live push.** Portal relays (STOMP on both sides, BFF rule kept).
2. **Q2: Offline conflicts.** First synced wins; later ones `DUPLICATE_OFFLINE`; rows never rewritten. PROJECT.md §P7 wording updated.
3. **Q3: Offline modes.** Check-in only.
4. **Q4: Emails.** Pass issued (QR + `.ics`); waitlisted / promoted / cancelled; event cancelled / rescheduled; reminders 24 h and 1 h.
5. **Q5: Recipients.** Ask event-service at send time (SERVICE identity); `RegistrationCancelled` + email/name.
6. **Q6: DLQ tooling.** Admin portal page + ADMIN endpoints + WARNING health + gauge.
7. **Q7: Dashboard.** Organizer and admin; first names in the ticker; volunteers get their gate's count.
8. **Q8: `.ics`.** notification-service; public endpoint; portal button.
9. **Q9: Waitlisted email.** New `RegistrationWaitlisted` event.
10. **Q10: Sending.** In the listener, retry → DLQ (rare duplicate accepted).
11. **Q11: Email language.** English only.
12. **Q12: Offline age.** 12 h back, 2 min ahead → `REJECTED_OFFLINE_CLOCK`.

**Defaults the owner can still change before the pre-phase commit:**

13. **Q13: Revoked-pass email.** Not sent (not in Q4's list; the wallet shows REVOKED). *Default: none.*
14. **Q14: Redis in tests** via Testcontainers (as PostgreSQL and RabbitMQ already are). *Default: yes.*
15. **Q15: Emails in tests** with GreenMail (a test-scoped jar, pinned in the root POM) rather than a Mailpit container. *Default: GreenMail.*
16. **Pre-phase commit:** the owner commits this plan (and the T09.1 doc changes) before T09.2 starts.

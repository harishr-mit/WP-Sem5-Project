# MITPass demo script (about 10 minutes)

Before the audience arrives: `docker compose up --build`, wait until `docker compose ps` shows everything healthy and the seed has finished (about two minutes after the services; `docker compose logs event-service | grep "Bulk seed done"`). Open these tabs: portal http://localhost:8090, Eureka :8761, Mailpit :8025, Grafana :3000 (dashboard *MITPass overview*), Zipkin :9411, and Swagger http://localhost:8080/swagger-ui.html. Reset any time with `docker compose down -v` and `up` again.

**One thing to know:** logins are rate-limited per browser address (5 in a row, then one every 6 seconds) and every browser on the laptop shares one address inside Docker, so switching between accounts quickly can show *Too many login attempts*. Log in to each account once beforehand (the sessions stay), or start the stack with `LOGIN_BURST_CAPACITY=300 LOGIN_REPLENISH_RATE=10 docker compose up -d` (phones on the LAN keep their own address).

**Accounts** (all passwords `Passw0rd!`): `admin@mitpass.test`; `organizer.a@mitpass.test` (owns the fest's opening night and most events); `volunteer01@mitpass.test` (assigned to the opening night's **Main** gate; `volunteer02` North, `03` South, `04` East, and so on); students `2027500001@student.annauniv.edu`, `2027501001@…` (the seed's students; the first 1,100 are registered for the opening night). The fest: **MITPass Fest: Opening Night** (4 gates, 1,200 seats) plus 11 smaller events, two of them tiny with a waitlist (*Robo Wars*, *Inter-department Cricket*).

## 1. The architecture (1 min)

Show [ARCHITECTURE.md](ARCHITECTURE.md)'s picture, then the **Eureka** dashboard (the services up) and **Swagger** at the gateway (every service's API in one place). *Point out:* a database per service, events over RabbitMQ.

## 2. A student gets a pass (2 min)

1. Portal → **Sign up** with a fresh `2099123456@student.annauniv.edu` → open **Mailpit**, click the verification link → log in.
2. **Explore** → *Fest Hackathon* → **Register**. The page says *You're in* and the pass appears within a second.
3. **Mailpit**: the pass email arrives with the QR inline and a calendar invite. Portal → **My passes**: the wallet card with its **rotating QR** (a new code every 30 seconds, valid 45 s).

*Point out:* the pass was made by a different service (pass-service) after a message; the QR is a signed token anyone can verify against `/.well-known/pass-jwks.json` (`scripts/VerifyPassToken.java`).

## 3. No overbooking (1 min)

`./mvnw -pl services/event-service -am verify -Dtest=SeatClaimConcurrencyTest`: 500 parallel registrations for 100 seats → exactly 100 confirmed, 400 waitlisted, `seats_taken == 100`. In the portal, *Robo Wars* shows **Full: you'll join the waitlist**; cancel one confirmed registration and watch the first waitlisted student move up.

## 4. The gate (3 min)

1. As **admin**: *My events / Events* → **MITPass Fest: Opening Night** → **Close registrations**, then **Start now** (the admin override; normally the scheduler starts it at the right time).
2. Phone A: log in as student `2027500001@student.annauniv.edu` → **My passes** → the live QR. Phone B (or a second browser window): log in as `volunteer01@mitpass.test` → **Scan** → gate *Main* → scan phone A.
   - ✅ **Green**: *Welcome, …*. Scan again → ❌ **ALREADY_CHECKED_IN**.
   - Wait a minute, scan a stale screenshot of the QR → ❌ expired. Revoke another student's pass (organizer → *Registrations* → **Revoke**) and scan it → ❌ revoked.
   - Typed pass number (the Luhn check digit catches a typo) works as the manual fallback.
3. **Organizer** (`organizer.a`) → the event → **Live**: the counter ticks up with every scan, with arrivals per 5 minutes, per-gate counts and the recent-scans ticker.
4. A wrong check-in? **Reports → Void check-in** (needs a reason; the scan stays in the audit trail and the pass can be scanned again).

## 5. Offline (1 min)

On the scanner phone: open the scan page once, switch Wi-Fi off, scan three passes → the badge says **Offline · 3 queued** (signatures are checked in the browser with WebCrypto). Switch Wi-Fi on → the queue syncs; a pass that was checked in elsewhere meanwhile comes back **DUPLICATE_OFFLINE**.

## 6. Operations (2 min)

1. **Grafana** *MITPass overview*: requests/s, p95 latency, scans/min by result, registrations, outbox lag, dead-letter queues, breaker state.
2. **Zipkin** → search service `checkin-service`, open a `POST /api/scans` trace: gateway → check-in → event-service → pass-service, **crossing RabbitMQ** (the `inbox receive` span).
3. `docker compose stop pass-service`, then scan again: scanning **keeps working** (check-in has its own copy of the passes), *Admin · Health* shows pass-service **DOWN**, the Grafana breaker panel reacts. `docker compose start pass-service` to bring it back.

## 7. Admin powers (as time allows)

*Admin · Users*: search "priya" (indexed search), **Ban user** (the login stops, sessions end). Cancel a small event (the cancellation revokes every pass), then **Reinstate event**: students whose registration stands get **new** passes and emails (the old ones stay revoked).

## 8. The code tour (as time allows)

Walk the concept table in [ARCHITECTURE.md](ARCHITECTURE.md) and click through to each implementation. The load test is in [load-test/](load-test/) (1,000 students arriving in 10 minutes across 4 gates).

---
**If something looks off:** `docker compose ps` (health), `docker compose logs <service>`, RabbitMQ http://localhost:15672 (mitpass/mitpass) for stuck messages, *Admin · Dead letters* for failed ones.

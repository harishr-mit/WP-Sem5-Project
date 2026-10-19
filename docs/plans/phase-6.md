# Phase 6 plan: Web Portal (Spring MVC)

> **Source:** `PROJECT.md` §P6, `docs/DECISIONS.md` D9 (every slice shortcut listed there), the UI reference `MITPass App Screens.html` (PROJECT.md §2). **Tracked in:** `log.md` as T08, sub-tasks T08.1–T08.13 (same numbering as the steps in §15).
> **Written:** 2026-10-17, on branch `phase6` (from `main` after PR #5 and the review commit `544be95`). This plan is a guide: if a step turns out simpler or different in practice, do the sensible thing and record the deviation in `log.md` and in a closing "As built" section here.
> **Status:** Q1–Q4 answered by the owner on 2026-10-17 (§17) and recorded in PROJECT.md §4/§P6 and `docs/DECISIONS.md` D9/D15 (T08.1, done with this plan). Q5–Q10 are defaults the owner can still change. The owner commits the plan before any code is written.

## 1. Goal and exit criteria

**Goal:** turn the P3–P5 slices into a finished, server-rendered portal for all four roles. It follows the UI reference's look (maroon "passport cover", condensed display headings, monospace pass numbers, green/amber/red gate results) and shows Spring MVC done properly: form objects with inline `BindingResult` errors, Post/Redirect/Get, flash messages, CSRF, a `HandlerInterceptor`, custom error pages and i18n. The portal stays a BFF. Tokens live only in the server-side session, are refreshed automatically, and every call goes through the gateway. A volunteer scans with the phone's camera over HTTPS on the LAN.

**Exit demo** (all must pass before T08 is marked done):

1. **Full journey (PROJECT.md §P6 exit):** sign up → verify through the link in Mailpit (opens a **portal** page) → log in → find the event with the live search → register → the "You're in" page (screen 3) shows the pass **without a reload** → open the pass on phone A at `https://<laptop-ip>:8443` (pass card, rotating QR, countdown ring) → phone B, a volunteer on the scanner page, points its camera at it → **green full-screen flash, the attendee's name, a beep and a vibration**. Scanning again gives a red `ALREADY_CHECKED_IN`. A screenshot of the code 60 s later gives `EXPIRED_TOKEN`.
2. **Automatic refresh:** with auth-service started with `--mitpass.auth.access-token-ttl=1m`, a user keeps working for 5 minutes without being sent to the login page. Ten parallel requests from one session at expiry cause **exactly one** refresh call, and auth-service's reuse detection never fires (`TokenRefresherTest`; checked live in the auth-service log too). An expired or revoked refresh token sends the user to `/login?expired`.
3. **Organizer:** create an event with two sessions in the form (sessions added and removed in the sub-form). An invalid form shows errors next to the fields, both the portal's own Bean Validation errors and event-service's 400 field errors. Editing with a stale version shows the "someone changed this event, reload" message (409). Lifecycle actions show only the allowed moves, and critical ones ask for confirmation in a dialog (still enforced by the server). Add a gate, assign a volunteer by email, see registrations, revoke a pass, open the attendance page and download the CSV.
4. **Admin:** search users, change roles (granting ADMIN asks for confirmation), unlock a locked account, end a user's sessions, rotate the auth and pass signing keys (confirmation). The **health overview** lists every registered service as UP with its components (db, rabbit, discovery). Stopping pass-service turns it DOWN within one refresh.
5. **MVC fundamentals visible:** PRG on every form (refreshing after a post doesn't resubmit), flash messages, a missing CSRF token gives 403, custom **403 / 404 / 500** pages plus a "service unavailable" page when the gateway is down, the current user is set by a `HandlerInterceptor`, and a **language switch to Tamil** changes every label on the page.
6. **Look:** every page follows the UI reference in light **and dark mode** and works at phone width (360 px) without horizontal scrolling. The scanner and the wallet are phone-first.
7. **D9 closed:** every row of the D9 shortcut table is marked replaced (§6), with the replacement named.
8. **Security:** no rendered page contains a JWT or a refresh token (test over every page); `sec:authorize` hides links a role can't use, and the server refuses the URL anyway (403 page). The gateway's new admin endpoint is in its `EdgeSecurityTest`, and its row is in `docs/security-matrix.md`.
9. `./mvnw verify` green locally and in GitHub Actions.

## 2. Starting point and scope

**Built in P3–P5 (the D9 slices):** form login against auth-service with tokens in `PortalUser` (session), CSRF on, PRG; pages for the catalogue, event detail, register/cancel, my registrations, my passes, the pass page (live QR swapped every 30 s, RL6 fixed), organizer events (action buttons, RL8), confirmation pages (RL9), registrations + revoke, the scan page (typed number or pasted token, manual confirm), and the attendance page + CSV. `GatewayClient` already wraps every backend call the portal needs except those in §10. 62 portal tests.

**The backend already has what P6 needs:** event create/edit (`@Version`), sessions, venues, gates, volunteer assignment, registrations, passes and media, scans and lookup, attendance (including `/api/attendance/me` for stamps and certificates), user admin (search, roles, unlock, sessions), key rotation, token refresh. Three small backend changes are needed (§10).

**Owner decisions for this phase** (answers to §17, 2026-10-17; recorded in PROJECT.md and D15):

| Topic | Decision | PROJECT.md impact |
|---|---|---|
| CSS (Q1) | **Hand-written CSS with design tokens** (CSS custom properties), no Tailwind and no Node. The owner chose it as the faster, simpler option once the trade-off was explained. JS libraries come as **WebJars** through Maven (D1 pins them) | §4 tech stack: Tailwind removed; §2 UI reference "Thymeleaf + Tailwind" → "Thymeleaf + plain CSS" |
| HTTPS for phone cameras (Q2) | A **self-signed certificate made with the project JDK's `keytool`** (script), served by the portal under a `lan` profile on :8443. Each phone accepts the warning once. No global install, no trust-store change | §P6 "HTTPS dev cert (mkcert)" → "self-signed dev cert (keytool)"; §4 layout `infra/certs/` comment |
| Health overview (Q3) | **The gateway aggregates:** a new ADMIN-only `GET /api/admin/health` in api-gateway reads the instances from Eureka and each one's `/actuator/health` (status + component names, no details). The portal keeps its "everything through the gateway" rule | §P6 admin bullet names it |
| i18n (Q4) | **English + Tamil** bundles (`messages.properties`, `messages_ta.properties`), a language switch kept in a cookie. Claude drafts the Tamil; the owner (or a native speaker) checks it | §P6 MVC bullet |

**Out of scope (and where it lands):**

| Not in P6 | Phase | What P6 does instead |
|---|---|---|
| Live dashboard (WebSocket/STOMP, Redis counters, Chart.js arrivals chart) | P7 | Screen 7 styled with the figures the summary API has; a **Refresh** button (HTMX) updates them in place |
| Emails, `.ics` invites, reminders (notification-service) | P7 | Screen 3's timeline shows "Seat reserved" and "Pass issued" with real times; the email and reminder lines appear once P7 sends them. No "Add to calendar" button yet (Q6) |
| Offline scanning (PWA, IndexedDB queue, WebCrypto) | P7 | The scanner works online. Its JS is kept in one module (`scanner.js`) so P7 can add the offline path |
| Undoing a check-in, admin overrides of final states | after P8 | Still parked: `docs/REVIEW-LATER.md` RL10, RL12 |
| Waitlist after registration closes | after P8 | RL1: the plan's rule stays; the event page's wording follows the plan, not the mockup |
| Gatling, Grafana, Zipkin | P8 | — |

## 3. Module layout after Phase 6

```
platform/api-gateway/              # + AdminHealthController (GET /api/admin/health, ADMIN), EdgeSecurity rule
services/event-service/            # + allowedTransitions on EventSummaryResponse (list endpoint)
services/auth-service/             # verify-link default → the portal's /verify-email page
services/*/application.yml         # management.endpoint.health.show-components: always
scripts/make-dev-cert.ps1, .sh     # NEW: self-signed PKCS#12 for the lan profile (project JDK keytool)
infra/certs/                       # portal-dev.p12 lands here (gitignored)
web-portal/
  pom.xml                          # + webjars-locator-lite, htmx.org, html5-qrcode (WebJars)
  src/main/java/com/mitpass/portal/
    auth/                          # + TokenRefresher (per-session lock), SessionTokens; SessionExpiryFilter → refresh first
    client/                        # GatewayClient + refresh-and-retry on 401, new calls (§10); ApiModels additions
    config/                        # + WebMvcConfig (interceptor, locale resolver + change interceptor, formatters)
    web/                           # controllers by area: events, my, organizer, scan, admin, account, errors
    web/forms/                     # @ModelAttribute form objects: EventForm (+ SessionRow), GateForm, AssignForm, RolesForm, …
    web/support/                   # CurrentUserInterceptor, HtmxSupport (HX-Request, HX-Trigger), ApiErrors → BindingResult
  src/main/resources/
    messages.properties, messages_ta.properties
    templates/layout.html          # page shell: head, header/nav per role, flash, footer, dark-mode + language switch
    templates/error/403.html, 404.html, 500.html, 503.html
    templates/{auth,events,my,organizer,scan,admin}/…   (§5)
    static/css/tokens.css, portal.css                   # design tokens, then components (§9)
    static/fonts/                  # self-hosted woff2 (OFL) + OFL.txt (Q8)
    static/js/portal.js, pass-card.js, scanner.js, theme.js
```

## 4. Key decisions for this phase

- **One page shell, plain Thymeleaf fragments.** `layout.html` has a `page(title, content)` fragment that each template calls with `th:replace="~{layout :: page(#{…}, ~{::main})}"`. No Layout Dialect dependency.
- **HTMX for partial updates, the server stays in charge.** Every HTMX action is an ordinary MVC endpoint that returns a fragment (`templates/…:: fragment`) when `HX-Request` is set and the full page otherwise, so every page also works without JavaScript and every URL can be bookmarked. Patterns used: live search (`hx-get` with `delay:300ms`, `hx-push-url`), polling until something exists (the new pass, the waitlist position; stops once done via HTTP 286), forms swapped in place on a validation error, dialogs for confirmations. A confirmation dialog posts `confirmed=true`; the server still refuses a post without it (RL9 unchanged).
- **Form objects + `BindingResult`.** Each form has its own class in `web/forms` with Bean Validation for what the portal can check itself. The service's 400 `errors[]` are copied into the same `BindingResult` (`ApiErrors.copyInto`) so both kinds of error look the same next to the field. A 409 `stale-version` becomes a global error with a reload link.
- **The current user through a `HandlerInterceptor`** (`CurrentUserInterceptor` puts `currentUser` in the model in `postHandle`), replacing the `@ModelAttribute` in `PortalAdvice`. PROJECT.md §P6 asks for an interceptor. `PortalAdvice` keeps the exception handlers.
- **Errors.** `ApiProblemException` 403 → `error/403`, 404 → `error/404`; `ResourceAccessException` (gateway or service down) → `error/503` ("try again shortly", correlation id shown); anything else → `error/500` with the correlation id, never a stack trace. Spring Boot's `/error` is covered by `error/{status}.html` templates.
- **Automatic token refresh** (§7), with a per-session lock, because auth-service's reuse detection (D7) would end the session if two requests refreshed with the same token.
- **Scanner** (§8): `html5-qrcode` for the camera. The page posts the decoded text to the portal, which calls `POST /api/scans` and returns a result fragment. The token never leaves the server-side flow except as the QR content the camera read.
- **HTTPS only where needed.** Only the portal faces the phone's browser, so only the portal gets the `lan` profile (:8443, PKCS#12 from `scripts/make-dev-cert`). Gateway and services stay on plain HTTP on localhost.
- **Health through the gateway** (Q3): the gateway is reactive, so its admin endpoint uses `ReactiveDiscoveryClient` + `WebClient` with a 2 s timeout per instance, and reports a non-answer as DOWN ("no answer in 2 s"). Here the gateway authorizes the role itself (ADMIN), because it is the service. D7 already lets a service own its rules; the D15 entry says so.
- **No new state, no new contracts beyond one additive field** (`allowedTransitions` on the event list). Everything else uses existing endpoints.

## 5. Page map

| Route | Screen | Who | Backend calls | MVC point it shows |
|---|---|---|---|---|
| `/signup` | 1 Register | anyone | `POST /api/auth/signup`, `GET /api/departments` | `@ModelAttribute SignupForm`, inline errors from both sides, PRG to `/check-email` |
| `/check-email` | — | anyone | — | flash attribute carries the address |
| `/verify-email?token=` | — | anyone | `POST /api/auth/verify-email` (on button press) | GET shows a "Confirm my email" button; POST does it (link prefetchers can't consume the token) |
| `/login` | — | anyone | form login → `POST /api/auth/login` | "resend verification" form when the email isn't verified |
| `/events` | Explore | anyone | `GET /api/events` (q, category, department, from, to, page), `GET /api/categories` | HTMX live search + filters, `hx-push-url`, pagination fragment |
| `/events/{id}` | 2 Event | anyone | event, eligibility (logged in) | seats bar, eligibility line, register button by state |
| `POST /events/{id}/register` → `/events/{id}/registered` | 3 Pass issued | student | `POST /api/registrations` (Idempotency-Key), then `GET /api/passes/me` (polled) | PRG; HTMX poll until the pass exists, then the "Open my pass" button |
| `/my/registrations` | — | logged in | `GET /api/registrations/me` | status + waitlist position, HTMX refresh while waitlisted; cancel (dialog) |
| `/my/passes`, `/my/passes/{id}` | 4 QR code | logged in | passes, `qr/live` PNG, `ticket.pdf` (pass-through) | pass card with countdown ring; locked / waitlist / revoked states |
| `/my/stamps` | 4 (stamps) | logged in | `GET /api/attendance/me` | attendance record + certificate progress (Q5) |
| `/my/profile` | 4 (profile) | logged in | `GET /api/users/me`, `DELETE /api/users/me/sessions` | "log out everywhere" (dialog) (Q5) |
| `/organizer/events` | 7 nav: Events | organizer, admin | `GET /api/events?organizerId=` (with `allowedTransitions`) | action buttons from the list, one call (D9 row) |
| `/organizer/events/new`, `/{id}/edit` | — | organizer, admin | venues, categories, departments, `POST`/`PUT /api/events`, sessions CRUD | `EventForm` with a dynamic `sessions[i]` sub-form (HTMX add/remove row), `@Version` → 409 message |
| `/organizer/events/{id}/registrations` | 7 nav: Registrations | owner, admin | registrations + passes | table with filter, revoke dialog |
| `/organizer/events/{id}/staff` | 7 nav: Volunteers | owner, admin | gates CRUD, volunteers assign/unassign | `GateForm`, `AssignForm` (by email; 422 "not a volunteer" shown on the field) |
| `/organizer/events/{id}/attendance` | 7 Mark attendance | owner, admin | summary, attendees, certificates, CSV | styled screen 7, Refresh button (HTMX), CSV pass-through |
| `/scan` | 5 Scan, 6 Verify | volunteer, organizer | assignments, gates, `POST /api/scans`, lookup | full-screen camera, mode switch, manual tab, result fragment + `HX-Trigger` for flash/sound |
| `/admin/users`, `/admin/users/{id}` | — | admin | `GET /api/users`, roles, unlock, sessions | `RolesForm` (checkboxes), confirmation for ADMIN |
| `/admin/health` | — | admin | `GET /api/admin/health` | auto-refresh every 10 s (HTMX), UP/DOWN badges |
| `/admin/keys` | — | admin | `POST /api/auth/keys/rotate`, `POST /api/passes/keys/rotate` | dialog confirmation |
| `/error/*` | — | anyone | — | custom 403/404/500/503 |

Navigation follows the mockup: students get **Explore · My passes · Stamps · Profile** (a bottom bar on phones, the header on wider screens); organizers get **Events · Registrations · Volunteers · Reports** inside an event; volunteers get **Scan**; admins get **Users · Health · Keys**. A user with several roles sees each group.

## 6. D9 shortcuts and their replacements (the "P6 is done" checklist)

| D9 shortcut | Replaced by (step) |
|---|---|
| Log in again after 15 min | `TokenRefresher` (T08.3) |
| Minimal styling, plain search form, full reloads | design tokens + components, HTMX live search, dark mode, responsive (T08.2, T08.4) |
| Verification link opens the auth-service API | `/verify-email` portal page; auth-service `verify-link` default (T08.3) |
| Spring's default error page | `error/403/404/500/503`, i18n bundles (T08.2, T08.11) |
| Live QR `<img>` on a plain 30 s timer | pass card with countdown ring, preload-then-swap kept, QR shown at a whole multiple of its size (RL6 cause b) (T08.5) |
| Scan page with a typed number or pasted token | camera scanner over HTTPS, manual tab, flash, sound, vibration (T08.8, T08.9) |
| Attendance page updates on reload | styled screen 7 + Refresh button; live push stays P7 (T08.7) |
| Registration status / waitlist position on reload | HTMX refresh while PENDING / WAITLISTED (T08.4) |
| "My events" loads each event's detail | `allowedTransitions` on the list endpoint (T08.6) |
| New pass appears on reload | HTMX poll on the "You're in" page (T08.4) |
| QR image and PDF passed through byte for byte | kept (BFF rule), countdown ring around it (T08.5) |
| Lifecycle labels and confirmation pages as plain server pages | labels in the bundles, confirmations as HTMX dialogs, still server-enforced (T08.6, T08.11) |

## 7. Token refresh

- `TokenRefresher.ensureFresh(request)` runs before every gateway call (in the `GatewayClient` interceptor) and in `SessionExpiryFilter`. If the access token expires within `mitpass.portal.refresh-ahead` (60 s, but at most a quarter of the token's lifetime; added in T08.13, §18), it refreshes.
- **One refresh per session at a time:** it locks the session mutex (`WebUtils.getSessionMutex`), then **reads the user from the session again**. If another request has already refreshed, it uses that result. Only then does it call `POST /api/auth/refresh`.
- The new `PortalUser` (new access token, new refresh token, new `expiresAt`, roles re-read from the new token) replaces the old one in the `SecurityContext` and is **saved explicitly** through the `SecurityContextRepository` (Spring Security 6+ doesn't save it automatically).
- A 401 from the gateway on a call that carried a token triggers one refresh and one retry. A second 401, or a refresh that fails (expired, revoked, reused), ends the session → `/login?expired`.
- Requests without a session (static files, public pages) don't refresh.
- Roles changed by an admin reach the user's pages at the next refresh (≤ 15 min), with no new login. That is a small improvement on today.

## 8. Scanner

- **Page:** dark, full-screen (screens 5–6). Header: gate selector (assigned gates plus own events' gates, RL11) and an Online indicator. A **Check in / Check out** switch, the camera frame, and a **Type pass no.** tab (the existing lookup → visual check → confirm flow).
- **Camera:** `html5-qrcode` (`Html5Qrcode` with the back camera, `qrbox` frame, 10 fps). A decoded text is ignored while a scan is in flight, and the same text is ignored for 3 s (one QR stays in view for many frames).
- **Submit:** `htmx.ajax('POST', '/scan/submit', {values: {token, gateId, mode, deviceId}})` with the CSRF header from a `<meta>` tag. The portal calls `POST /api/scans` and renders `scan/result :: card`. The response header `HX-Trigger: {"scanResult": {"color": "green"}}` makes `scanner.js` flash the screen (green / amber / red), play a WebAudio tone (rising for green, low for red; no audio files) and vibrate (`navigator.vibrate`, 1 short / 3 short).
- **Result card:** the code's message, the attendee's name, the pass number, the session and the check-in time (from `ScanAnswer`), then **Scan next**. The camera stays paused until then, so the volunteer reads the result. An amber `UNKNOWN_PASS` says to send the person to the help desk.
- **Device id:** a random id made once and kept in `localStorage` (already sent with each scan since P5; P7's offline queue uses it).
- **Counter:** "Scanned on this device" counts this session's accepted scans. Volunteers can't read the event summary (owner/admin only), and the live gate numbers come with P7 (Q9).
- **No camera:** if permission is denied or there is no camera (or the page isn't HTTPS), the page opens on the manual tab and says why.

## 9. Design system

- **Tokens** (`tokens.css`, from the mockup): `--maroon #7A1F3D`, `--maroon-deep #3A0F1E`, `--gold #E9CF8B`, `--ink #13203A`, `--slate #4A5872`, `--teal #17606B`, `--teal-deep #0F4750`, `--line #D5DBE6`, `--ground #F3F5F9`, `--card #FFFFFF`, `--ok #1E7A46`, `--ok-bright #3FB97A`, `--warn #A65F00`, `--err #B3261E`; scanner `--night #0D1424`, `--scan-cyan #8FD3DC`. Spacing and radius scales, and shadows.
- **Dark mode:** the same token names redefined under `@media (prefers-color-scheme: dark)` (unless the user chose light) and under `[data-theme="dark"]`. A toggle in the header stores the choice in a cookie, so the server renders the right `data-theme` with no flash. The scanner is always dark.
- **Fonts:** Big Shoulders Display (headings, the passport cover), Hanken Grotesk (body), IBM Plex Mono (pass numbers, the MRZ lines on the pass card). Latin subsets are self-hosted as woff2 with the OFL licence file. No font CDN, so the demo works on a closed LAN and P7's offline shell can cache them (Q8). Tamil text falls back to the system's Tamil font (Noto Sans Tamil / Latha).
- **Components:** header/nav (+ phone bottom bar), button (primary, secondary, danger, link), form field (label, hint, error), card, **pass card** (passport cover, holder, gate, pass number, MRZ line, status chip, QR area with the SVG countdown ring), seats bar, status badge per state, table (stacks into cards on phones), flash, dialog, stamp, stat tile, result card (scanner), empty state.
- **Responsive:** mobile-first. Breakpoints at 40 rem and 64 rem; tables become card lists below 40 rem.
- **Accessibility basics:** colour is never the only signal (result cards carry the word and an icon), focus rings, labels on every field, `aria-live` on the scan result and on HTMX-updated regions, contrast checked for both themes.

## 10. Backend changes

| Where | Change | Contract note | Test |
|---|---|---|---|
| event-service `EventSummaryResponse` + mapper | add `allowedTransitions` (same rule as the detail: `status.manualNext()`) | additive field | `EventControllerTest` list case; organizer page test makes one call |
| auth-service `application.yml` | `verify-link` default → `http://localhost:8090/verify-email?token={token}` (`MITPASS_VERIFY_LINK` still overrides it) | email content only | `AuthFlowTests` link assertion |
| api-gateway `AdminHealthController` | `GET /api/admin/health` (ADMIN): every Eureka instance → `{service, instanceId, url, status, components{name: status}, checkedAt}` | new endpoint | `AdminHealthTest` (stubbed discovery + two fake instances, one down / slow), `EdgeSecurityTest` (anonymous 401, student 403, admin 200) |
| every service + gateway + discovery `application.yml` | `management.endpoint.health.show-components: always` (names and status only; details stay hidden) | — | gateway test reads component names |
| portal `GatewayClient` | new calls: refresh, verify-email + resend, venues, event create/update, sessions CRUD, gates CRUD, volunteers, users admin, key rotation ×2, admin health, attendance `/me`, users `/me`, log out everywhere | — | `GatewayClientTest` per call shape |

## 11. Configuration additions

```yaml
# web-portal
mitpass:
  portal:
    refresh-ahead: 60s          # refresh the access token this long before it expires
    search-delay: 300ms         # HTMX live search debounce
    poll-interval: 2s           # "You're in" page polls for the new pass
    poll-timeout: 30s           # then says "taking longer than usual, check My passes"
    health-refresh: 10s         # admin health page
    scan-repeat-ignore: 3s      # the same QR text is ignored this long on the scanner
    supported-locales: en,ta
spring:
  messages:
    basename: messages
    fallback-to-system-locale: false
  thymeleaf:
    cache: true                 # P6: on by default; the dev profile turns it off
---
spring.config.activate.on-profile: lan
server:
  port: 8443
  ssl:
    bundle: portal-dev          # spring.ssl.bundle.jks.portal-dev → ../infra/certs/portal-dev.p12
```

```yaml
# api-gateway
mitpass:
  gateway:
    admin-health:
      timeout: 2s
```

## 12. Ports and running locally

- Unchanged: Eureka :8761, gateway :8080, auth :8081, event :8082, pass :8083, checkin :8084, portal :8090.
- **Phone demo:** run `scripts/make-dev-cert.ps1` once (makes `infra/certs/portal-dev.p12` for `localhost`, `127.0.0.1` and the laptop's current LAN IPs, valid 1 year), then start the portal with `--spring.profiles.active=lan` → `https://<laptop-ip>:8443`. Each phone accepts the certificate warning once. **The owner allows inbound TCP 8443 in Windows Firewall** (one `New-NetFirewallRule` command, as administrator). That is a system setting, so the agent doesn't run it. The command is in README.md "Quick start" step 1 (*Phone demo*), added 2026-10-17 for anyone cloning the repo; the owner sets it up on this laptop. Phones and laptop must be on the same Wi-Fi, without client isolation (some campus networks block it; a phone hotspot works).
- No new database, no `docker compose down -v`.

## 13. Tests

| Test | Kind | Covers |
|---|---|---|
| `TokenRefresherTest` | unit + threads | refresh ahead of expiry; 10 parallel callers → 1 refresh; failed refresh ends the session; the saved context holds the new tokens |
| `GatewayClientTest` (additions) | `MockRestServiceServer` | every new call's method, path and body; 401 → refresh → retry once |
| `ErrorPagesTest` | MockMvc | 403/404/500/503 pages render with the correlation id, no stack trace |
| `I18nTest` | MockMvc + bundle scan | `?lang=ta` switches and sticks (cookie); **every `#{key}` used in a template exists in both bundles, and both bundles have the same keys** |
| `NoTokensInPagesTest` | MockMvc over every GET page | no `eyJ…` JWT, no refresh token in any rendered page |
| `CurrentUserInterceptorTest` | MockMvc | `currentUser` in the model for logged-in pages, absent otherwise |
| `EventPagesTest`, `RegistrationFlowTest` | MockMvc | live-search fragment vs full page (HX-Request), filters, poll returns 286 once the pass exists |
| `OrganizerFormsTest` | MockMvc | `EventForm` binding with `sessions[i]`, add/remove row fragment, portal + API field errors in one `BindingResult`, 409 stale version, PRG |
| `StaffingPagesTest`, `AdminPagesTest` | MockMvc | gate/assign forms, 422 on the field, roles + confirmation for ADMIN, unlock, health page |
| `ScanPagesTest` (updated) | MockMvc | `/scan/submit` fragment + `HX-Trigger` colour per result code; manual tab still works without JS |
| Existing `PortalPagesTest`, `PassPagesTest` | MockMvc | updated to the new markup; RL6 regression test kept |
| gateway `AdminHealthTest`, `EdgeSecurityTest` | WebTestClient | §10 |
| event-service list test | MockMvc | `allowedTransitions` on the list |

JavaScript (`scanner.js`, `pass-card.js`, `theme.js`) is small and checked live in the exit demo on two real phones, not with a browser test framework. If something there breaks twice, add a test page rather than a framework.

## 14. Documentation to update at T08.13

`README.md` (portal walkthrough per role, the phone demo: cert script, `lan` profile, firewall rule, accepting the warning), `PROJECT.md` (§P6 ticks + status), `docs/DECISIONS.md` (D9 table: every row "replaced in P6 by …"; D15 as built; D1 WebJar pins), `docs/security-matrix.md` (gateway admin endpoint), `docs/http/phase-6.http` (refresh, verify-email, admin health, event list with `allowedTransitions`), AGENTS.md parts table (Web portal row: design tokens, bundles, refresh lock; gateway row: admin endpoint), "As built" here, `docs/REVIEW-LATER.md` (RL1 note on the event page wording if it changes), `log.md`.

## 15. Steps (log.md T08.1–T08.13)

Each step ends with `./mvnw -pl <module> -am verify` green, a full `./mvnw verify` after any step that touches more than the portal, and a `log.md` entry.

| # | Step | Size | Done when |
|---|---|---|---|
| T08.1 | **Owner decisions recorded** (Q1–Q4) in PROJECT.md §2/§4/§P6 and DECISIONS (D9 note, D15 draft) | S | done with this plan (2026-10-17) |
| T08.2 | **Foundation:** WebJars (+ D1 pins), `layout.html`, tokens + base components, fonts, dark mode, `WebMvcConfig` (interceptor, cookie locale resolver), English bundle skeleton, error pages + 503 mapping | M | `ErrorPagesTest`, `CurrentUserInterceptorTest` green; existing pages render in the new shell |
| T08.3 | **Session and account:** `TokenRefresher` + 401 retry, `SessionExpiryFilter` refreshes first; `/verify-email`, `/check-email`, resend; auth-service verify-link default | M | `TokenRefresherTest` (1 refresh for 10 callers), auth-service suite green |
| T08.4 | **Student catalogue and registration:** HTMX live search + filters + paging, event detail (screen 2), register → "You're in" (screen 3) with pass poll, my registrations with waitlist refresh, cancel dialog | M | `EventPagesTest`, `RegistrationFlowTest` green |
| T08.5 | **Wallet:** pass cards, pass page (screen 4: passport card, countdown ring, QR at whole-multiple size), locked/waitlist/revoked states, PDF; **Stamps** and **Profile** (Q5) | M | `PassPagesTest` updated + RL6 test green |
| T08.6 | **Organizer events:** event-service `allowedTransitions` on the list; events page in one call; `EventForm` create/edit with sessions sub-form, venue picker, inline errors, 409 reload; action labels + HTMX confirmation dialogs | L | `OrganizerFormsTest` green; event-service suite green |
| T08.7 | **Organizer staffing and reports:** registrations table + revoke dialog, gates + volunteer assignment page, attendance page (screen 7, Refresh button, certificates, CSV) | M | `StaffingPagesTest` green, attendance tests updated |
| T08.8 | **Scanner:** full-screen camera page, `/scan/submit` fragment + `HX-Trigger`, flash/sound/vibration, repeat-ignore, manual tab, device id + counter, no-camera fallback | L | `ScanPagesTest` green; camera checked in a desktop browser on `localhost` (secure context) with a QR on a second screen |
| T08.9 | **HTTPS for phones:** `scripts/make-dev-cert.ps1` / `.sh`, `lan` profile with an SSL bundle, `.gitignore` (`*.p12`), README steps | S | portal answers on `https://localhost:8443`; phone check in T08.13 |
| T08.10 | **Admin:** gateway `GET /api/admin/health` + `show-components` in every service; users list/search, roles (ADMIN confirmation), unlock, sessions; health page; key rotation page | M | `AdminHealthTest`, `EdgeSecurityTest`, `AdminPagesTest` green |
| T08.11 | **i18n:** every template string moved to `messages.properties`; Tamil bundle; language switch; lifecycle labels and result-code messages from the bundles | M | `I18nTest` green (same key sets, no missing key); **the owner has reviewed the Tamil text** (log.md T34: ask the owner when the bundle is drafted, or earlier on request) |
| T08.12 | **Polish sweep:** D9 checklist (§6) item by item, dark mode and 360 px pass on every page, accessibility basics, `NoTokensInPagesTest` | M | every §6 row ticked; `NoTokensInPagesTest` green |
| T08.13 | **Exit demo + docs** (`phase-6.http`, README, PROJECT ticks, D9/D15, as built, log) | M | all nine exit criteria pass on two real phones; nothing left running |

Order: T08.1 → T08.2 → T08.3 → T08.4 → T08.5 → T08.6 → T08.7 → T08.8 → T08.9 → T08.10 → T08.11 → T08.12 → T08.13. Strings go straight into `messages.properties` from T08.2 on; T08.11 adds Tamil and sweeps for leftovers. T08.9 can move before T08.8 if camera testing needs the phone early.

## 16. Gotchas to check at build time

1. **Refresh-token reuse:** two requests refreshing with the same token revoke the whole family (D7). Hence the session lock and the re-read after taking it (§7). The QR image poll and an HTMX poll can run at the same moment as a page load.
2. **Spring Security 6+ doesn't save a changed `SecurityContext`** on its own. Save it through the repository after a refresh, or the old token comes back on the next request.
3. **Thymeleaf JavaScript inlining** drops the default text after an inlined expression (RL6). Inline finished values into their own `const`, and keep the RL6 regression test.
4. **HTMX and CSRF:** HTMX requests need the CSRF header (`hx-headers` on `<body>` from the `_csrf` meta tags). Spring Security's 403 on a missing token should still render `error/403`, not an empty body.
5. **HTMX + PRG:** an HTMX form post that succeeds answers with `HX-Redirect` (so the URL changes like a normal PRG). One that fails re-renders the form fragment with status 422, which HTMX 2 swaps only if `htmx.config.responseHandling` allows it. Set that once in `portal.js`.
6. **Polling stop:** HTMX stops polling on HTTP **286**. Return it once the pass exists or the waitlist is resolved.
7. **Camera needs a secure context:** `localhost` counts as one, but a LAN IP over plain HTTP does not. On iOS, `html5-qrcode` needs `playsinline` and a user gesture to start. Test both phones in T08.13.
8. **`html5-qrcode` 2.3.8 is the last release (2023)** (Q10). Pin it as a WebJar. If it fails on a current phone browser, fall back to the native `BarcodeDetector` where available and keep the manual tab.
9. **Sessions sub-form binding:** removed rows leave index gaps (`sessions[0]`, `sessions[2]`). Bind into a list and drop empty rows in the controller, or renumber the inputs in the fragment. Existing sessions are updated, new ones created, missing ones deleted, all against event-service's session endpoints (several calls; a failure part-way shows what was saved).
10. **Self-signed cert SANs:** the certificate must name the laptop's LAN IP (`-ext SAN=ip:…`), or the phone refuses it even after the warning. The IP changes between networks, so rerun the script there.
11. **Tamil text** is wider and taller than English. Buttons and table headers must wrap, not clip; check the 360 px pass in Tamil too.
12. **Fonts and the OFL:** keep `OFL.txt` next to the woff2 files, and serve them with long cache headers (`spring.web.resources.cache.cachecontrol`).
13. **Template cache:** `spring.thymeleaf.cache=true` by default now, so local template edits need the `dev` profile (or a restart).
14. **Windows editing:** use the Write tool or Python with `encoding="utf-8", newline=""` (log.md). The Tamil bundle must be UTF-8 (Spring Boot reads `.properties` bundles as UTF-8 by default; check `spring.messages.encoding`).

## 17. Owner questions

**Answered 2026-10-17:**

1. **Q1: CSS.** The owner first asked which option is faster, easier and less work. Answer: hand-written CSS, because there is no build tooling (no Node, plugin, lock file or CI change), the mockup's own CSS turns straight into tokens, and the portal is small (~25 templates, ~20 components). Tailwind would have kept the planned stack and its utility classes at the cost of that setup. **Decision: hand-written CSS** (D15; PROJECT.md §4 drops Tailwind).
2. **Q2: HTTPS:** a self-signed certificate made with the project JDK's `keytool`, `lan` profile on :8443 (no mkcert, no trust-store change).
3. **Q3: Health overview:** the gateway aggregates (`GET /api/admin/health`, ADMIN).
4. **Q4: i18n:** English + Tamil, with a language switch. The owner reviews the Tamil text when it is drafted or whenever asked (log.md T34).

**Defaults the owner can still change before the pre-phase commit:**

5. **Q5: Stamps and Profile pages** (mockup screen 4's bottom bar) are in P6. Both use existing endpoints (`/api/attendance/me`, `/api/users/me`). *Default: yes.*
6. **Q6: "Add to calendar"** (`.ics`) waits for P7, because PROJECT.md §3 puts `.ics` in notification-service. *Default: no button until P7.*
7. **Q7: Attendance page (screen 7):** P6 styles it with the summary's figures and a Refresh button; no automatic polling, because live updates are P7's WebSocket work (D9). Panels that need data P5 doesn't collect yet (arrivals per 5 min, refused scans by reason) are left out until P7. *Default: as described.*
8. **Q8: Fonts** are self-hosted woff2 files committed under `static/fonts` with their OFL licence (about 150 KB; no WebJar exists for them), not loaded from Google Fonts. This keeps the closed-LAN demo and P7's offline shell working. *Default: as described.*
9. **Q9: Scanner counters:** volunteers see "scanned on this device" only. The mockup's "Scanned at this gate / Inside" figures need the event summary, which only owners and admins may read; live gate counts come with P7. *Default: as described.*
10. **Q10: `html5-qrcode`** (named in PROJECT.md §4) has had no release since 2023 (2.3.8), but it is still widely used. *Default: keep it, with the `BarcodeDetector` fallback from gotcha 8 if needed.*
11. **Pre-phase commit:** the owner commits this plan (and the T08.1 doc changes) before T08.2 starts. Done: `f1ebc27`.

**Asked during the build:**

12. **Q12: Draft events** (2026-10-17, T08.4). The public catalogue and `GET /api/events/{id}` returned DRAFT events to anyone. **Answer: hide drafts from everyone but their organizer and admins**, fixed in event-service with tests (`DraftVisibility`, `DraftVisibilityTests`; D15). Three older tests that read their own fresh draft without a token now send the owner's token.

## 18. As built (2026-10-18)

Built on branch `phase6` in the order of §15. Decisions as built: `docs/DECISIONS.md` D15 (and the D9 table, every row
replaced). Demo requests: `docs/http/phase-6.http`. Browser checks: `web-portal/ui-checks/` (Playwright on Node,
installed Edge; AGENTS.md *Working rules*, added mid-phase at the owner's request, T36).

### Exit criteria

| # | Criterion | Result |
|---|---|---|
| 1 | Full journey | **Browser, all green:** `signup.mjs` 7/7 (sign-up → Mailpit link opens `http://localhost:8090/verify-email` → the button confirms → log in), `student.mjs` 30/30 (live search → register → "You're in" shows the pass without a reload → pass card with SVG code and a running countdown), `scanner.mjs` 11/11 (Priya's real live QR fed to the browser as its camera: green *Admitted* with the flash colour in `HX-Trigger`, then amber *Already in*, then the manual tab). `EXPIRED_TOKEN` for an old code is covered by checkin-service's pipeline tests (Phase 5). **Open: the two real phones** (camera, beep, vibration, certificate warning, Wi-Fi) for the owner's exit demo |
| 2 | Automatic refresh | auth-service started with `--mitpass.auth.access-token-ttl=1m`; `refresh.mjs 5` (2026-10-19): 30 rounds of 5 parallel page requests over 5 min, all 200, still logged in; auth-service issued 7 refresh tokens for that login (6 rotations, about one per token lifetime), none revoked, no reuse detected. `TokenRefresherTest`: 10 parallel callers → 1 refresh; `SessionRefreshTest` (a real session): the new tokens are saved and a refused refresh token ends on `/login?expired`. A bug found live is fixed (below) |
| 3 | Organizer | `organizer.mjs` 17/17; `OrganizerFormsTest` (sessions sub-form, portal and API field errors in one `BindingResult`, stale-version 409, PRG), `StaffingPagesTest`, attendance page with the CSV link |
| 4 | Admin | `admin.mjs` 10/10 (6 instances UP with their components); `AdminPagesTest` (ADMIN grant confirmation, can't remove your own ADMIN, unlock, end sessions, key rotation); live: stopping pass-service showed it DOWN on the next refresh |
| 5 | MVC fundamentals | PRG + flash on every form; `PortalPagesTest` (forms and login without CSRF → 403), `ErrorPagesTest` (403/404/500/503 with the correlation id), `ShellTest` (the interceptor's user, theme and language), `I18nTest` + `tamil.mjs` 10/10 |
| 6 | Look | `sweep.mjs` 120/120 (every role's pages, dark mode, 360 px and desktop: no sideways scroll, fields labelled, images with alt, buttons named) |
| 7 | D9 closed | all 12 rows of the D9 table name their replacement |
| 8 | Security | `NoTokensInPagesTest` (22 pages, one user with every role), `sec:authorize` on the navigation with server-side 403s, the gateway endpoint in `AdminHealthTest` (anonymous 401, student and organizer 403, admin 200) and in `docs/security-matrix.md` |
| 9 | Build | `./mvnw verify` green locally (2026-10-19), 589 tests: common-web 27, common-security 13, security-test-support 17, common-messaging 22, common-pass 14, discovery 1, gateway 23, auth 59, event 136, pass 63, checkin 58, portal 156. GitHub Actions runs once the branch is pushed (the owner pushes) |

### Different from the plan

- **Refresh window:** `refresh-ahead` (60 s) is capped at a quarter of the token's lifetime, worked out from the token's `iat`/`exp` (`PortalUser.issuedAt`, `tokenLifetime()`). With 1-minute tokens the uncapped window covered the whole lifetime, so every request refreshed: 95 rotations in 3.5 min (none reused, so no logout, but pointless load). With the cap, 4.
- **Browser tests:** §13 planned to check the JavaScript only on real phones. After the owner's Playwright rule (T36), `ui-checks/` holds ten scripts, and they found about twenty problems MockMvc can't see (below). They are not part of `./mvnw verify`; CI keeps the MockMvc tests.
- **Test names:** `CurrentUserInterceptorTest` became `ShellTest`; `RegistrationFlowTest` is part of `EventPagesTest`; the gateway endpoint's security cases are in `AdminHealthTest`, not `EdgeSecurityTest`. Also new: `AccountPagesTest`, `MyAccountPagesTest`, `SessionRefreshTest`, `RefreshClientTest`, `DraftVisibilityTests` (event-service). Portal suite: 156 tests (62 before Phase 6).
- **`theme.js`** is part of `portal.js` (theme, CSRF header for HTMX, `responseHandling` for 422, dialogs).
- **Explore** opens on upcoming events, with an *Include past events* filter (the seed has past events, and they came first).
- **Scanner result** is a bottom sheet over the camera (at phone height the result below the camera was off-screen).
- **Draft events** are hidden from everyone but their organizer and admins (Q12, found while building the catalogue; event-service change with tests).
- **Gate name** is required in the portal form too (the API requires it; the form had it optional).
- **Organizer's list** asks for `size=100` (it showed only the API's default 20 events).
- **Static files** are content-hashed (`/css/portal-<hash>.css`) with a one-year cache, fonts included; the version pattern has to match hashed names too (`/favicon*.svg`).

### Bugs found and fixed during the phase

Found by tests or review: DRAFT events public (Q12); the first refresh test passed for the wrong reason (MockMvc's `authentication()` keeps no session, now `SessionRefreshTest` with a real session); a null role filter threw in the admin users page; the gateway controller had two constructors; a `@Primary` fake discovery client clashed with Spring Cloud's composite one; `SignupForm` messages were hard-coded English.

Found in the browser: after login the user landed on the favicon (its hashed URL wasn't public); Explore opened on past events; the countdown ring overlapped the code; the phone header wrapped to two rows; filters took five rows; the machine-readable line wrapped (now 30 characters); a 4 px sideways scroll (`.field` min-width); form rows misaligned and the session *Remove* overlapping; the scanner result off-screen; "Scan next" and initials invisible on the dark scanner; letter-spacing tore Tamil glyph clusters apart, and a Tamil word overflowed the stamps page; dark-mode danger buttons white on pink; the organizer list stopped at 20 events.

Found live: the refresh window (above).

### After the first two-phone try (2026-10-19, log.md T08.14)

The network problems are in `archive/2026-10-19_phone-demo_network-diagnosis.md` (laptop as hotspot, firewall rules,
`scripts/phone-demo-check.ps1`). Then the camera couldn't read the code: it was a 113×113-module code (level H, 467-character
token) drawn 232 px wide. Owner decisions: error correction **M** with a 15 % badge, light event colours darkened (D12,
PROJECT.md §P4). Also built: pass-service reads each code back and switches mask when ZXing can't find it (2.4 % of codes,
any level); the pass page draws the code larger and shows it full screen on a tap; the scanner asks for 1080p with
continuous focus, uses the browser's barcode detector where there is one and offers zoom. The UI-check scripts accept the
`lan` profile's certificate (`PORTAL_URL=https://localhost:8443`).

### Closed 2026-10-19

- **Two-phone exit demo** (criterion 1): done by the owner, laptop as hotspot; the camera reads the code even when it fills only part of the box (native detector at full resolution).
- **Tamil review by the owner** (log.md T34): done, including the strings added in T08.14.
- Full `./mvnw clean verify`: 593 tests (pass-service 65, portal 158).

### Open

- **GitHub Actions** on the pushed branch (the owner pushes).

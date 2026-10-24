# Portal UI checks (Playwright)

Browser checks of `web-portal` against a running stack (AGENTS.md, "Web UI checks use Playwright"). They take
screenshots at phone and desktop width, in light and dark mode, walk real flows (HTMX swaps, dialogs, the pass
card's timer, the camera scanner) and fail on console errors. They are **not** part of `./mvnw verify`; CI keeps the
MockMvc tests.

```bash
cd web-portal/ui-checks
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm ci     # project-local; uses the installed Edge, downloads no browser
node student.mjs <screenshot-folder>           # needs the stack from README.md "Quick start" (portal :8090, gateway :8080)
```

Each script prints one `PASS` / `FAIL` line per check and exits non-zero if any failed.

| Script | What it checks |
|---|---|
| `signup.mjs` | Sign up in the portal → the verification email in Mailpit (:8025) links to the **portal** → the page asks for a press (opening the link spends nothing) → log in as the new student |
| `student.mjs <dir>` | Creates a demo event through the API (organizer A), then as Priya: log in → live search → event → register → "You're in" (waits for the pass) → pass card (the countdown runs) → registrations, stamps, profile, a 404; then the same pages in dark mode and at desktop width. Writes PNGs and `report.json` |
| `organizer.mjs <dir>` | Organizer A: My events, the event form (adding a session swaps in place and keeps typed values, inline errors, PRG + flash), the cancel and revoke dialogs, registrations filter, gates, volunteer assignment, Reports refresh |
| `scanner.mjs <dir>` | Makes an event that goes LIVE within a minute, registers Priya, assigns the volunteer, then feeds Priya's **real live QR** to the browser as its camera (Chromium's fake video capture, made by `qr-video.mjs`): green *Admitted*, amber *Already in*, then the manual tab |
| `qr-video.mjs <png> <y4m>` | Helper for `scanner.mjs`: PNG → Y4M video, Node only (no image libraries) |
| `admin.mjs <dir>` | User search, the ADMIN grant confirmation (not applied), the health overview (every instance and its components), the key-rotation dialogs (opened and closed). Changes nothing |
| `tamil.mjs <dir>` | Key pages in Tamil at phone width: no missing-key marker (`??…??`), no sideways scroll. The screenshots are for the owner's review of the wording |
| `sweep.mjs <dir>` | Every role's pages in dark mode at phone and desktop width: no sideways scroll, every field labelled, images with alt text, buttons and links named. `VERBOSE=1` lists every page |
| `refresh.mjs [minutes]` | Exit criterion 2: start auth-service with `--mitpass.auth.access-token-ttl=1m`, then this sends five parallel page requests every 10 s; nobody is sent to log in (default 3 min) |
| `p7.mjs <dir>` | Phase 7: emails in Mailpit (waitlisted → promoted → pass with inline QR and `.ics`; cancelled), Add to calendar, the organizer's Live tab moving within ~1 s of a scan, WebCrypto and Luhn on real data, offline scans queued and synced (a pass checked in online meanwhile comes back DUPLICATE_OFFLINE), the service worker serving `/scan` offline, a poison message → DLQ → amber health → Admin · Dead letters → discard. Needs notification-service, Redis and the RabbitMQ management API (:15672) |
| `p8.mjs <dir>` | Phase 8: the waitlist setting on the event form, "full, no waitlist" on the event page (no register button), the admin's ban dialog and Edit links; phone and desktop, light and dark, no console errors. Logs in once per role: the gateway rate-limits logins per address |
| `p8-flows.mjs <dir>` | Phase 8, state-changing, through the real UI and checked against the API: admin Close registrations → **Start now** (dialog, event LIVE); a scan, then the organizer's **Void check-in** (reason, attendance back to 0); revoke → **Issue a new pass**; cancel (code typed) → **Reinstate** (new pass, old one stays revoked); **Ban** → login refused → **Lift ban**. `SCHEME=dark WIDTH=360` for the phone run. Creates its own events, so it can run again; wait a minute between runs (login rate limit) |
| `theme.mjs <dir>` | The theme switch on a **dark system** with no saved choice: it offers light (sun), one click → light, another → dark; the active sub-tab is gold in system dark |
| `reminders.mjs` | Phase 7: the 1 h reminder arrives once per registration with **two** notification-service instances running (start both with `--mitpass.notifications.reminders.interval=10s`, the second on another `--server.port`) |
| `lan.mjs https://<laptop-ip>:8443` | The phone-demo setup from a desktop browser: with the `lan` profile the page is a secure context (the camera needs one) and the session cookie is `Secure` |

Environment: `PORTAL_URL` (default `http://localhost:8090`), `GATEWAY_URL` (default `http://localhost:8080`),
`MAILPIT_URL` (default `http://localhost:8025`), `PW_CHANNEL` (default `msedge`; set it empty to use Playwright's own
Chromium from the user cache).

What a desktop browser can't stand in for (the phone camera itself, sound and vibration, accepting the certificate
and reaching the laptop over the Wi-Fi) is checked by hand on two real phones in the exit demo.

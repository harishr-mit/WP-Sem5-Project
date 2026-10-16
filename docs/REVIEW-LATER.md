# Review for later

Points that came up during the build and need a conversation with the owner, but don't block the current phase. **After Phase 8, go through every open item here with the owner** (PROJECT.md §5, "After P8").

Add an item when a question doesn't need an answer now. Give it the next free id, the date, where it came from, the question, what was built for now, and the options. Don't delete items: mark them resolved with the date and the outcome.

| Status | Meaning |
|---|---|
| OPEN | Waiting for the post-P8 review |
| RESOLVED | Decided; the outcome is written under the item |

---

## RL1: Waitlist after registration closes (UI mockup vs plan)

*2026-10-15 · OPEN · from: Phase 3 plan §10, owner asked to park it here*

**Question.** The mockup's event screen (screen 2) says "Registration closes Thu 15 Oct, 23:59. After that, you can join the waitlist." In the plan, the waitlist exists only while registration is open and the event is full. After closing, nobody can register or join the waitlist.

**Built for now.** The plan's rule. The portal's event page says "Full: you'll join the waitlist" while registration is open, and "Registration closed" after that.

**Options.** (a) Keep the plan's rule and change the mockup text. (b) Allow joining the waitlist after closing (until the event goes LIVE), so late students can still get a freed seat. That needs a new rule in `EligibilityPolicy` and the seat claim, and the scheduler's close step would leave waitlisting open.

## RL2: Can an organizer end an event early by hand?

*2026-10-15 · OPEN · from: owner's answer on scheduler scope (Phase 3)*

**Question.** The owner decided that events go LIVE only through the scheduler (a LIVE button would be redundant), and that an organizer can undo an accidental early LIVE. The answer didn't cover COMPLETED.

**Built for now.** The scheduler moves LIVE → COMPLETED at `endsAt`, and the organizer can also press COMPLETED (for example, to end a seminar early).

**Options.** (a) Keep the button. (b) Make COMPLETED scheduler-only, like LIVE.

## RL3: Undoing LIVE after check-ins have started

*2026-10-15 · OPEN · from: owner's answer on scheduler scope (Phase 3)*

**Question.** In P3 an organizer can move LIVE → REGISTRATION_CLOSED to undo an accidental early start. Once check-in exists (P5), attendance rows may already exist when the organizer does that.

**Built for now.** P3 allows the undo at any time while LIVE (there is no attendance yet). To stop the scheduler from making the event LIVE again at its next run, the organizer must move `startsAt` later; otherwise it goes LIVE again within one scheduler interval.

**Options.** (a) Refuse the undo once any check-in exists (409). (b) Allow it and keep the attendance rows. (c) Allow it only within N minutes of going LIVE.

**P5 note (2026-10-16).** Check-in now exists. Built for now: option (b). The undo is allowed and attendance rows are kept; scans get `NOT_YET_OPEN` until the event is LIVE again, and a pass already checked in stays checked in for that session.

## RL4: Check the dead-letter handling end to end

*2026-10-16 · OPEN · from: owner, after the Phase 4 plan (Q2: minimal DLQ in P4, completed in P7)*

**Question.** P4 pulls a minimal dead-letter queue forward for pass-service (3 retries, then `pass-service.inbox.dlq`), and P7 is meant to complete it: DLQs for every consumer, inspect/replay tooling, and an alert or health signal when a DLQ isn't empty (PROJECT.md §P4, §P7; `docs/DECISIONS.md` D13). After P8, check with the owner that this really happened.

**Built for now.** P4: pass-service's inbox has a DLQ; dead-lettered messages sit there (visible in the RabbitMQ UI) and are replayed by hand.

**To check.** (a) Every consumer (pass-, checkin-, notification-service) has a DLQ declared with the `common-messaging` helper. (b) A poison message ends up in its DLQ after the retries, not lost and not looping. (c) Dead-lettered messages can be inspected and replayed, and a replay is still idempotent. (d) A non-empty DLQ shows up in health or Grafana. (e) Anything still missing is fixed or consciously accepted.

## RL5: PDF ticket file name: event name + student id

*2026-10-16 · OPEN · from: owner, after trying the Phase 4 portal*

**Request.** Name the downloaded ticket `<event name>-<student id>.pdf` instead of the pass number.

**Built for now.** `GET /api/passes/{id}/ticket.pdf` sends `Content-Disposition: attachment; filename="<pass number>.pdf"` (for example `TECHFEST26-000042-7.pdf`); the portal passes the header through unchanged (`PassMediaController.ticket`, `MyPassesController.ticket`).

**To decide.** (a) Which "event name": the title ("TechFest Hackathon", needs spaces and punctuation made file-safe) or the code (`TECHFEST26`)? (b) Which "student id": the register number from the token's `regNo` claim (Gmail sign-ups have none, so it needs a fallback, for example the email's local part), or the user id (UUID, unreadable)? pass-service doesn't store the register number today, so (b) may need a new column copied from `RegistrationConfirmed` (a new contract field) or the caller's token. (c) Use `filename*` (RFC 6266) for non-ASCII titles.

## RL6: The live QR on the pass page "glitches", apparently after the first 30 seconds

*2026-10-16 · **RESOLVED 2026-10-16** (owner asked to look after it with the Phase 5 plan) · from: owner, after trying the Phase 4 portal*

**Outcome.** Reproduced by rendering the page's script with Thymeleaf. **None of the suspected causes below was the real one.** Thymeleaf's JavaScript inlining drops the default text after an inlined expression, so `/*[[${refreshSeconds}]]*/ 30 * 1000` rendered as `30`. The page swapped the image every **30 ms** instead of 30 s, minting about 33 tokens and PNGs a second. Fixed in `web-portal` (`MyPassesController` passes `refreshMillis`; `my/pass.html` inlines it into its own `const` and preloads the next image, swapping it only once loaded, which is option (a)). Regression test: `PassPagesTest.liveQrRefreshesEveryThirtySecondsNotEveryThirtyMilliseconds`. Cause (b) (the 400 → 280 px downscale with `pixelated`) is cosmetic and goes to P6 with the pass card. Details: `docs/plans/phase-5.md` §20.

**Observed.** On the portal's pass page (`/my/passes/{id}`), the live QR seems to glitch, apparently from the first 30 s refresh on.

**Built for now.** A D9 slice shortcut: a plain `setInterval` assigns a new `src` (`/my/passes/{id}/qr.png?t=…`) to the `<img>` every 30 s; each request mints a new 45 s token and renders a new PNG (400 px, shown at 280 px with `image-rendering: pixelated`).

**Suspected causes (unverified).** (a) Swapping `src` in place blanks or half-paints the image while the next PNG loads (a flicker on every refresh). (b) Downscaling 400 → 280 px with `pixelated` gives uneven module widths, so the pattern looks jagged and changes visibly with each new token. (c) A slow or failed request fires `onerror`, which hides the image. Reproduce first (browser dev tools: network timing, the `img` element).

**Options.** (a) Preload the next image with `new Image()` and swap only on `load`. (b) Render at the display size (or a whole multiple of the module count) and drop `pixelated`. (c) Fold it into P6, which replaces this timer with the pass card and countdown ring (D9), and fix the cause there.

## RL7: pass-service's event copy has no reconciler yet

*2026-10-16 · **RESOLVED 2026-10-16** (owner: "take care of it too") · from: Phase 5 (owner's Q6 answer: "manual means human error is possible; avoid relying on users")*

**Outcome.** Option (a), built in Phase 5: pass-service's `EventCopyReconciler` (every 5 min, first run 30 s after startup) re-reads status and times of each event with valid passes from event-service's public catalogue and applies any difference through the message handlers. Tested (`EventCopyReconcilerTest`: lost cancellation revokes, lost reschedule moves the times, a second run changes nothing, an outage is skipped) and shown live: an event cancelled while pass-service was down, with the message purged from its queue, had its pass revoked (`EVENT_CANCELLED`) 30 s after the restart. D14.

**Question.** In P5 the owner chose automatic reconciliation for checkin-service's copies (D14). pass-service has a copy too, from P4: `event_ref` (event times and status), filled only from `EventStateChanged` / `EventRescheduled`. If such a message were lost (purged queue, parked in the DLQ), pass-service would not run the cancellation saga (passes of a cancelled event stay valid until expiry) or follow a reschedule.

**Built for now.** Messages only (outbox, durable inbox, idempotent consumer, DLQ). The loss needs manual interference or a poison message. checkin-service's gate check doesn't depend on it: it re-reads event status from event-service itself, so a scan at a cancelled event is still refused.

**Options.** (a) Give pass-service the same kind of job: re-read the status and times of events with valid passes from event-service's public catalogue every few minutes, and run the saga for any that turn out CANCELLED. No service identity is needed (the catalogue is public). (b) Fold it into P7's DLQ work (an alert when a DLQ isn't empty). (c) Accept the risk.

## RL8: Rename the lifecycle buttons ("Move to" column)

*2026-10-16 · OPEN · from: owner, after trying the portal*

**Request.** The organizer's *My events* page has a "Move to" column whose buttons show raw state names (`PUBLISHED`, `REGISTRATION_OPEN`, `CANCELLED`, …). Use direct action words instead: **Publish**, **Open registrations**, **Cancel**, and likewise for the rest.

**Built for now.** `organizer/events.html` prints each allowed transition's enum name (`th:text="${to}"`) under the heading "Move to"; CANCELLED is styled red. A P3/P4 D9 slice shortcut.

**To decide.** The label for every move: PUBLISHED → "Publish", REGISTRATION_OPEN → "Open registrations", REGISTRATION_CLOSED → "Close registrations", COMPLETED → "End event", LIVE → REGISTRATION_CLOSED (undo) → "Undo start", CANCELLED → "Cancel event"? And the column heading ("Actions"). Labels belong in P6's i18n message bundles, keyed by target state, so the API's state names stay unchanged.

## RL9: Ask for confirmation before critical actions

*2026-10-16 · OPEN · from: owner, after trying the portal ("accidental clicks should not cancel an event")*

**Request.** A critical button must ask "are you sure?" before it acts, so one accidental click can't cancel an event.

**Built for now.** No confirmation anywhere: the lifecycle buttons (including **Cancel**, which also revokes every pass of the event through the saga, and can't be undone, see RL10), **Revoke** on a pass, and the volunteer's check-out post straight away. Only the manual check-in has a confirm step (the visual name check).

**To decide.** (a) Which actions count as critical: at least cancel event, revoke pass, end event, undo start; perhaps close registrations and role changes (admin, P6). (b) How: a confirmation page or dialog (PRG keeps working; HTMX in P6 could show a modal), possibly typing the event code for the most destructive ones (cancel). (c) Enforce it in the portal only, or also require an explicit `confirm=true` in the API for the irreversible calls.

## RL10: Admin powers, including undoing a cancellation

*2026-10-16 · OPEN · from: owner, after trying the portal ("admin can revoke a cancelled event; admin can do pretty much anything")*

**Request.** An admin should be able to reverse a cancellation (bring a CANCELLED event back), and in general be able to do almost anything.

**Built for now.** CANCELLED is a final state for everyone, admins included (enum table, PROJECT.md §3; D10). Cancelling starts the saga: every valid pass becomes REVOKED, which is also final (D12). Registrations stay as they were. Admins already pass every ownership check (they can edit any event and move it through the same transitions as its organizer), but they can't leave a final state.

**To decide.** (a) Which state an un-cancelled event returns to (the one it was cancelled from, recorded in `event_state_history`?). (b) What happens to the passes revoked by the saga: re-issue new passes (new numbers, `PassIssued`, emails in P7), or allow REVOKED → ISSUED/ACTIVE for `EVENT_CANCELLED` revocations only (a new edge in the pass state machine, a change to the D12 contract that scanners and the P7 offline revocation list must follow). (c) Whether students who cancelled meanwhile or were waitlisted are affected. (d) "Admin can do pretty much anything" more broadly: list the other overrides wanted (for example force LIVE, reopen a COMPLETED event, edit a LIVE event, un-revoke a single pass), each as an admin-only edge with its own audit row and confirmation (RL9).

## RL11: An organizer can also be a volunteer

*2026-10-16 · OPEN · from: owner, after trying the portal*

**Request.** An organizer should be able to work as a volunteer too, for example scanning at the gate of their own event.

**Built for now.** Roles are a set per user (auth-service, many-to-many since P2), so one account *can* already hold ORGANIZER and VOLUNTEER together, but only if an admin grants VOLUNTEER (`PUT /api/users/{id}/roles`). The seeded organizers have ORGANIZER only. Scanning needs the VOLUNTEER role (`ScanController`, D7) **and** an assignment to the gate's event (`volunteer_assignment`, the pipeline's first step); assigning someone also requires them to hold VOLUNTEER (`StaffingService.assign`, 422 otherwise). So today an organizer cannot scan at their own event unless an admin adds the role and someone assigns them. With both roles, the portal header shows both *My events* and *Scan*.

**To decide.** (a) Should every organizer get VOLUNTEER automatically (an implied role: ORGANIZER includes VOLUNTEER), or should it stay an explicit extra role an admin grants? (b) May an organizer scan at their **own** events without an assignment (ownership counts as assignment to every gate), or only where assigned like anyone else? (c) May an organizer assign themselves (today the volunteer must hold VOLUNTEER; an organizer adding themselves is allowed by the ownership rule once they have it)? (d) Admins: same question (RL10, "admin can do pretty much anything"). (e) Whatever is chosen: `SecurityMatrixTest` gets an "organizer (owner) scanning" column, and scan logs keep recording the scanner's own id so audits still show who scanned.

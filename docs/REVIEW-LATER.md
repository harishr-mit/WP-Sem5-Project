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

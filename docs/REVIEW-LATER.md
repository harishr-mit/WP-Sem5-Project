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

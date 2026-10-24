package com.mitpass.event.domain;

/** What caused a state change, recorded in the history tables. */
public enum TransitionTrigger {
    /** An organizer, admin or student pressed a button. */
    MANUAL,
    /** The time-based lifecycle scheduler. */
    SCHEDULER,
    /** A side effect of another change (for example, a waitlist promotion). */
    SYSTEM,
    /** An admin override that no organizer may make (RL10, D17): un-cancel, force LIVE, reopen. */
    ADMIN
}

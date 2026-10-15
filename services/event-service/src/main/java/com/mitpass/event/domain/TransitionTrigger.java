package com.mitpass.event.domain;

/** What caused a state change, recorded in the history tables. */
public enum TransitionTrigger {
    /** An organizer, admin or student pressed a button. */
    MANUAL,
    /** The time-based lifecycle scheduler. */
    SCHEDULER,
    /** A side effect of another change (for example, a waitlist promotion). */
    SYSTEM
}

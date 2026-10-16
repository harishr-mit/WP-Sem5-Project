package com.mitpass.pass.domain;

/** What caused a pass state change, recorded in {@code pass_history} (same values as event-service, D10). */
public enum TransitionTrigger {
    /** An organizer or admin pressed a button. */
    MANUAL,
    /** The pass lifecycle scheduler (unlock, expiry). */
    SCHEDULER,
    /** A domain event from another service (registration confirmed or cancelled, event cancelled or completed). */
    SYSTEM
}

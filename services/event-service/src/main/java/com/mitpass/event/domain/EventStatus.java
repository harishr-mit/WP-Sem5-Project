package com.mitpass.event.domain;

/**
 * Event lifecycle (PROJECT.md §3). Phase 1 only stores it: new events are {@link #DRAFT}. The transition table and the
 * {@code transition()} guard arrive in Phase 3.
 */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    REGISTRATION_OPEN,
    REGISTRATION_CLOSED,
    LIVE,
    COMPLETED,
    CANCELLED
}

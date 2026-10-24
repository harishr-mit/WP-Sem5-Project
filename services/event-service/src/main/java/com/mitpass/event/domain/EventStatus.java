package com.mitpass.event.domain;

import com.mitpass.common.web.state.LifecycleState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Event lifecycle (PROJECT.md §3, docs/DECISIONS.md D10). {@link #allowedNext()} is the whole table;
 * {@link #manualNext()}, {@link #adminNext()} and {@link #scheduledNext()} narrow it to what an organizer may press,
 * what an admin may override (RL10, D17) and what the scheduler may do. LIVE is entered only by the scheduler; organizers may undo an accidental early start (LIVE →
 * REGISTRATION_CLOSED).
 */
public enum EventStatus implements LifecycleState<EventStatus> {
    DRAFT,
    PUBLISHED,
    REGISTRATION_OPEN,
    REGISTRATION_CLOSED,
    LIVE,
    COMPLETED,
    CANCELLED;

    private static final Map<EventStatus, Set<EventStatus>> NEXT = new EnumMap<>(EventStatus.class);
    private static final Map<EventStatus, Set<EventStatus>> SCHEDULED = new EnumMap<>(EventStatus.class);
    private static final Map<EventStatus, Set<EventStatus>> ADMIN_ONLY = new EnumMap<>(EventStatus.class);

    static {
        NEXT.put(DRAFT, EnumSet.of(PUBLISHED));
        NEXT.put(PUBLISHED, EnumSet.of(REGISTRATION_OPEN, CANCELLED));
        NEXT.put(REGISTRATION_OPEN, EnumSet.of(REGISTRATION_CLOSED, CANCELLED));
        NEXT.put(REGISTRATION_CLOSED, EnumSet.of(LIVE, CANCELLED));
        NEXT.put(LIVE, EnumSet.of(REGISTRATION_CLOSED, COMPLETED));
        NEXT.put(COMPLETED, EnumSet.noneOf(EventStatus.class));
        NEXT.put(CANCELLED, EnumSet.noneOf(EventStatus.class));

        SCHEDULED.put(PUBLISHED, EnumSet.of(REGISTRATION_OPEN));
        SCHEDULED.put(REGISTRATION_OPEN, EnumSet.of(REGISTRATION_CLOSED));
        SCHEDULED.put(REGISTRATION_CLOSED, EnumSet.of(LIVE));
        SCHEDULED.put(LIVE, EnumSet.of(COMPLETED));

        // RL10 (D17): admin overrides. Un-cancel goes back only to the state the event was cancelled from (Event).
        ADMIN_ONLY.put(CANCELLED, EnumSet.of(PUBLISHED, REGISTRATION_OPEN, REGISTRATION_CLOSED));
        ADMIN_ONLY.put(REGISTRATION_CLOSED, EnumSet.of(LIVE)); // start now
        ADMIN_ONLY.put(COMPLETED, EnumSet.of(LIVE)); // reopen
    }

    /** The whole table, the admin overrides (RL10) included; who may make which move is narrowed below. */
    @Override
    public Set<EventStatus> allowedNext() {
        Set<EventStatus> next = EnumSet.noneOf(EventStatus.class);
        next.addAll(NEXT.get(this));
        next.addAll(ADMIN_ONLY.getOrDefault(this, EnumSet.noneOf(EventStatus.class)));
        return Collections.unmodifiableSet(next);
    }

    /** What an organizer or admin may do from here: the table without LIVE. */
    public Set<EventStatus> manualNext() {
        Set<EventStatus> next = EnumSet.noneOf(EventStatus.class);
        next.addAll(NEXT.get(this));
        next.remove(LIVE);
        return Collections.unmodifiableSet(next);
    }

    /** What an admin may do from here: everything an organizer may, plus the overrides (RL10). */
    public Set<EventStatus> adminNext() {
        Set<EventStatus> next = EnumSet.noneOf(EventStatus.class);
        next.addAll(manualNext());
        next.addAll(ADMIN_ONLY.getOrDefault(this, EnumSet.noneOf(EventStatus.class)));
        return Collections.unmodifiableSet(next);
    }

    /** What the time-based scheduler may do from here. */
    public Set<EventStatus> scheduledNext() {
        return Collections.unmodifiableSet(SCHEDULED.getOrDefault(this, EnumSet.noneOf(EventStatus.class)));
    }

    /** Before the event starts its fields can change; LIVE, COMPLETED and CANCELLED events are read-only. */
    public boolean isEditable() {
        return this != LIVE && this != COMPLETED && this != CANCELLED;
    }

    /** From registration opening on, the code is fixed: pass numbers (Phase 4) contain it. */
    public boolean isCodeFrozen() {
        return this != DRAFT && this != PUBLISHED;
    }
}

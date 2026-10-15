package com.mitpass.event.domain;

import com.mitpass.common.web.state.LifecycleState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Registration lifecycle (PROJECT.md §3). PENDING exists only inside the registering transaction: the row is inserted
 * as PENDING and becomes CONFIRMED or WAITLISTED before commit, so outsiders never see it.
 */
public enum RegistrationStatus implements LifecycleState<RegistrationStatus> {
    PENDING,
    CONFIRMED,
    WAITLISTED,
    CANCELLED;

    private static final Map<RegistrationStatus, Set<RegistrationStatus>> NEXT =
            new EnumMap<>(RegistrationStatus.class);

    static {
        NEXT.put(PENDING, EnumSet.of(CONFIRMED, WAITLISTED));
        NEXT.put(WAITLISTED, EnumSet.of(CONFIRMED, CANCELLED));
        NEXT.put(CONFIRMED, EnumSet.of(CANCELLED));
        NEXT.put(CANCELLED, EnumSet.noneOf(RegistrationStatus.class));
    }

    @Override
    public Set<RegistrationStatus> allowedNext() {
        return Collections.unmodifiableSet(NEXT.get(this));
    }

    /** Active registrations count against the one-per-student-per-event rule ({@code uk_registration_active}). */
    public boolean isActive() {
        return this != CANCELLED;
    }
}

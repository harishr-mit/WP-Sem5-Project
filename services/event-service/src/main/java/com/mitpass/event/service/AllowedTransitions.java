package com.mitpass.event.service;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStatus;
import java.util.List;

/**
 * The moves the current caller may make, for {@code allowedTransitions}: an admin also sees the overrides (RL10),
 * everyone else the organizer's moves. The scheduler-only LIVE is never in an organizer's list.
 */
public final class AllowedTransitions {

    private AllowedTransitions() {}

    public static List<EventStatus> of(Event event) {
        return List.copyOf(
                isAdmin() ? event.adminTransitions() : event.getStatus().manualNext());
    }

    public static boolean isAdmin() {
        return CurrentUser.current().map(user -> user.hasRole(Roles.ADMIN)).orElse(false);
    }
}

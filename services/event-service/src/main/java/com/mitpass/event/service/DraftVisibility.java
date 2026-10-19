package com.mitpass.event.service;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Who may see a DRAFT event (owner decision, 2026-10-17, docs/plans/phase-6.md §19): its own organizer and admins.
 * For everyone else a draft doesn't exist: it is left out of the catalogue, and its page and sessions answer 404 like
 * an unknown id (a 403 would confirm that it exists). Every other state is public, as before.
 */
public final class DraftVisibility {

    private DraftVisibility() {}

    /** True if {@code viewer} (empty = anonymous) may see an event in {@code status} organized by {@code organizer}. */
    public static boolean canSee(EventStatus status, UUID organizer, Optional<CurrentUser> viewer) {
        if (status != EventStatus.DRAFT) {
            return true;
        }
        return viewer.map(user -> user.hasRole(Roles.ADMIN) || user.id().equals(organizer))
                .orElse(false);
    }

    public static boolean canSee(Event event, Optional<CurrentUser> viewer) {
        return canSee(event.getStatus(), event.getOrganizerId(), viewer);
    }

    /** The same rule as a query filter for the catalogue. */
    public static Specification<Event> visibleTo(Optional<CurrentUser> viewer) {
        if (viewer.map(user -> user.hasRole(Roles.ADMIN)).orElse(false)) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> viewer.<jakarta.persistence.criteria.Predicate>map(user -> cb.or(
                        cb.notEqual(root.get("status"), EventStatus.DRAFT),
                        cb.equal(root.get("organizerId"), user.id())))
                .orElseGet(() -> cb.notEqual(root.get("status"), EventStatus.DRAFT));
    }
}

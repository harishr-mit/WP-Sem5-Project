package com.mitpass.event.service;

import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.Venue;
import java.time.Instant;

/**
 * Domain rules that need loaded entities (so Bean Validation can't check them). A broken rule is a 422; an edit the
 * event's current state doesn't allow is a 409.
 */
final class CatalogRules {

    private CatalogRules() {}

    static void requireCapacityFits(int capacity, Venue venue) {
        if (capacity > venue.getCapacity()) {
            throw new BusinessRuleException("Capacity %d exceeds the capacity of venue '%s' (%d)"
                    .formatted(capacity, venue.getName(), venue.getCapacity()));
        }
    }

    static void requireInsideEvent(Event event, String sessionTitle, Instant startsAt, Instant endsAt) {
        if (!event.covers(startsAt, endsAt)) {
            throw new BusinessRuleException("Session '%s' must lie within the event (%s to %s)"
                    .formatted(sessionTitle, event.getStartsAt(), event.getEndsAt()));
        }
    }

    static void requireYearRange(Integer minYear, Integer maxYear) {
        if (minYear != null && maxYear != null && minYear > maxYear) {
            throw new BusinessRuleException(
                    "minYear (%d) must not be greater than maxYear (%d)".formatted(minYear, maxYear));
        }
    }

    /** LIVE, COMPLETED and CANCELLED events (and their sessions) are read-only. */
    static void requireEditable(Event event) {
        if (!event.getStatus().isEditable()) {
            throw new ConflictException(
                    "Event %d is %s and can no longer be changed".formatted(event.getId(), event.getStatus()));
        }
    }

    /** From registration opening on, pass numbers (Phase 4) depend on the code. */
    static void requireCodeUnchanged(Event event, String newCode) {
        if (event.getStatus().isCodeFrozen() && !event.getCode().equals(newCode)) {
            throw new ConflictException("The event code can't change once registration has opened (event is %s)"
                    .formatted(event.getStatus()));
        }
    }
}

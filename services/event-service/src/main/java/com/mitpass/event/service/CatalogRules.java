package com.mitpass.event.service;

import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.Venue;
import java.time.Instant;

/** Domain rules that need loaded entities (so Bean Validation can't check them). Each breach is a 422. */
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
}

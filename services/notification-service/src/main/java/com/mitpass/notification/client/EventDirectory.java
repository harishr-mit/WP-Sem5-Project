package com.mitpass.notification.client;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What notification-service reads from event-service, the owner of events and registrations (D14, D16). */
public interface EventDirectory {

    record Venue(String name, String building, String mapUrl) {}

    /** {@code version} grows with every edit; it is the invite's SEQUENCE. */
    record EventInfo(
            long id,
            String code,
            String title,
            Instant startsAt,
            Instant endsAt,
            String status,
            Long version,
            Venue venue) {

        public String place() {
            if (venue == null) {
                return null;
            }
            return venue.building() == null || venue.building().isBlank()
                    ? venue.name()
                    : venue.name() + ", " + venue.building();
        }
    }

    /** A registration as event-service returns it ({@code id} is the registration id). */
    record Recipient(UUID id, UUID userId, String userName, String userEmail, String status) {}

    /** One event from the public catalogue (short cache); empty for an unknown or hidden id. */
    Optional<EventInfo> event(long id);

    /** Drops the cached copy: a message said the event changed, so the next read must see the new times. */
    void forget(long id);

    /** Ids of the events starting in {@code [from, to)} (public catalogue). */
    List<Long> startingBetween(Instant from, Instant to);

    /** The event's registrations in this status, read with this service's SERVICE token. */
    List<Recipient> registrations(long eventId, String status);
}

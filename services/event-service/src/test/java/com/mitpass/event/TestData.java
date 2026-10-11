package com.mitpass.event;

import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.Venue;
import java.time.Instant;
import java.util.UUID;

/** Builders for test fixtures. */
public final class TestData {

    /** Owner of every fixture event. */
    public static final UUID ORGANIZER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    private TestData() {}

    public static Event event(String code, Category category, Venue venue, Instant startsAt) {
        Event event = new Event();
        event.setCode(code);
        event.setTitle("Event " + code);
        event.setDescription("Description of " + code);
        event.setCategory(category);
        event.setVenue(venue);
        event.setCapacity(Math.min(50, venue.getCapacity()));
        event.setStartsAt(startsAt);
        event.setEndsAt(startsAt.plusSeconds(3 * 3600));
        event.assignOrganizer(ORGANIZER_ID, "Test Organizer");
        return event;
    }
}

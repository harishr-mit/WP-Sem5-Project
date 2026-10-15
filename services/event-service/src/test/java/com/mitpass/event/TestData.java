package com.mitpass.event;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.event.domain.Category;
import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.Venue;
import com.mitpass.event.web.dto.EventCreateRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builders for test fixtures. */
public final class TestData {

    /** Owner of every fixture event. */
    public static final UUID ORGANIZER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    public static final CurrentUser ORGANIZER_USER = new CurrentUser(
            ORGANIZER_ID, "organizer@mitpass.test", "Test Organizer", Set.of("ORGANIZER"), null, null, null, "token");

    /** Far enough ahead that registration can always open. */
    public static final Instant FUTURE_START = Instant.parse("2099-11-14T03:30:00Z");

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

    /** An event in 2099 at venue 1 (Main Auditorium, V2 reference data), category 1. */
    public static EventCreateRequest createRequest(
            String code, int capacity, List<String> departments, Integer minYear, Integer maxYear) {
        return new EventCreateRequest(
                code,
                "Event " + code,
                null,
                1L,
                1L,
                null,
                capacity,
                FUTURE_START,
                FUTURE_START.plusSeconds(6 * 3600),
                FUTURE_START.minusSeconds(40L * 86_400),
                FUTURE_START.minusSeconds(86_400),
                null,
                departments,
                minYear,
                maxYear);
    }
}

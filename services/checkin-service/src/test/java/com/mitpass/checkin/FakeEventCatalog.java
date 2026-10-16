package com.mitpass.checkin;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** event-service as the tests set it up: events, gates and volunteer assignments in memory. No cache, no HTTP. */
public class FakeEventCatalog implements EventCatalog {

    private final Map<Long, EventInfo> events = new ConcurrentHashMap<>();
    private final Map<Long, List<GateInfo>> gates = new ConcurrentHashMap<>();
    private final Map<UUID, List<Assignment>> assignments = new ConcurrentHashMap<>();
    public final AtomicInteger eventCalls = new AtomicInteger();

    public void put(EventInfo event, GateInfo... eventGates) {
        events.put(event.id(), event);
        gates.put(event.id(), List.of(eventGates));
    }

    /** {@code gate} null = any gate of the event. */
    public void assign(UUID volunteer, EventInfo event, GateInfo gate) {
        assignments
                .computeIfAbsent(volunteer, id -> new ArrayList<>())
                .add(new Assignment(event.id(), event.code(), event.title(), event.startsAt(), gate));
    }

    public void unassignAll(UUID volunteer) {
        assignments.remove(volunteer);
    }

    @Override
    public EventInfo event(long eventId) {
        eventCalls.incrementAndGet();
        EventInfo event = events.get(eventId);
        if (event == null) {
            throw NotFoundException.of("Event", eventId);
        }
        return event;
    }

    @Override
    public List<GateInfo> gates(long eventId) {
        return gates.getOrDefault(eventId, List.of());
    }

    @Override
    public List<Assignment> assignmentsOf(CurrentUser volunteer) {
        return List.copyOf(assignments.getOrDefault(volunteer.id(), List.of()));
    }

    @Override
    public List<EventInfo> scanningWindow(Instant now, Instant until) {
        return events.values().stream()
                .filter(e -> "LIVE".equals(e.status())
                        || (List.of("REGISTRATION_OPEN", "REGISTRATION_CLOSED").contains(e.status())
                                && !e.startsAt().isAfter(until)
                                && e.endsAt().isAfter(now)))
                .toList();
    }
}

package com.mitpass.notification;

import com.mitpass.notification.client.EventDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** event-service in memory: events by id, registrations by event and status. */
public class FakeEventDirectory implements EventDirectory {

    private final Map<Long, EventInfo> events = new ConcurrentHashMap<>();
    private final Map<String, List<Recipient>> registrations = new ConcurrentHashMap<>();

    public void put(EventInfo event) {
        events.put(event.id(), event);
    }

    public void registrations(long eventId, String status, List<Recipient> recipients) {
        registrations.put(eventId + ":" + status, List.copyOf(recipients));
    }

    @Override
    public Optional<EventInfo> event(long id) {
        return Optional.ofNullable(events.get(id));
    }

    @Override
    public void forget(long id) {}

    @Override
    public List<Long> startingBetween(Instant from, Instant to) {
        return events.values().stream()
                .filter(e -> !e.startsAt().isBefore(from) && e.startsAt().isBefore(to))
                .map(EventInfo::id)
                .toList();
    }

    @Override
    public List<Recipient> registrations(long eventId, String status) {
        return registrations.getOrDefault(eventId + ":" + status, List.of());
    }
}

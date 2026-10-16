package com.mitpass.checkin.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mitpass.checkin.service.CheckinProblems;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * event-service over HTTP (through Eureka), with short Caffeine caches (docs/plans/phase-5.md §4): event details and
 * gates for {@code event-cache-ttl} (60 s), a volunteer's assignments for {@code assignment-cache-ttl} (30 s), keyed by
 * the volunteer's id, never the token. A failure without a cached answer is a 503 problem (circuit breakers: P8).
 */
public class HttpEventCatalog implements EventCatalog {

    private static final Logger log = LoggerFactory.getLogger(HttpEventCatalog.class);
    private static final ParameterizedTypeReference<List<GateInfo>> GATES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Assignment>> ASSIGNMENTS =
            new ParameterizedTypeReference<>() {};

    /** The part of the catalogue search page the window needs. */
    record SearchPage(List<EventInfo> content, int totalPages) {}

    private final RestClient rest;
    private final ZoneId zone;
    private final Cache<Long, EventInfo> events;
    private final Cache<Long, List<GateInfo>> gates;
    private final Cache<UUID, List<Assignment>> assignments;

    public HttpEventCatalog(RestClient rest, Duration eventTtl, Duration assignmentTtl, ZoneId zone, Clock clock) {
        this.rest = rest;
        this.zone = zone;
        this.events = cache(eventTtl, clock);
        this.gates = cache(eventTtl, clock);
        this.assignments = cache(assignmentTtl, clock);
    }

    private static <K, V> Cache<K, V> cache(Duration ttl, Clock clock) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(5_000)
                .ticker(() -> clock.millis() * 1_000_000L)
                .build();
    }

    @Override
    public EventInfo event(long eventId) {
        return events.get(
                eventId,
                id -> call(
                        "event " + id,
                        () -> rest.get().uri("/api/events/{id}", id).retrieve().body(EventInfo.class),
                        id));
    }

    @Override
    public List<GateInfo> gates(long eventId) {
        return gates.get(
                eventId,
                id -> call(
                        "gates of event " + id,
                        () -> rest.get()
                                .uri("/api/events/{id}/gates", id)
                                .retrieve()
                                .body(GATES),
                        id));
    }

    @Override
    public List<Assignment> assignmentsOf(CurrentUser volunteer) {
        return assignments.get(
                volunteer.id(),
                id -> call(
                        "assignments of " + id,
                        () -> rest.get()
                                .uri("/api/volunteers/me/assignments")
                                .headers(headers -> headers.setBearerAuth(volunteer.tokenValue()))
                                .retrieve()
                                .body(ASSIGNMENTS),
                        null));
    }

    @Override
    public List<EventInfo> scanningWindow(Instant now, Instant until) {
        // LIVE events whatever their dates; open/closed ones that start soon. Dates in the search are campus days.
        LocalDate from = LocalDate.ofInstant(now, zone).minusDays(2);
        LocalDate to = LocalDate.ofInstant(until, zone);
        Map<Long, EventInfo> found = new LinkedHashMap<>();
        for (EventInfo event : search("LIVE", null, null)) {
            found.put(event.id(), event);
        }
        for (String status : List.of("REGISTRATION_CLOSED", "REGISTRATION_OPEN")) {
            for (EventInfo event : search(status, from, to)) {
                if (!event.startsAt().isAfter(until) && event.endsAt().isAfter(now)) {
                    found.putIfAbsent(event.id(), event);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private List<EventInfo> search(String status, LocalDate from, LocalDate to) {
        List<EventInfo> all = new ArrayList<>();
        int page = 0;
        SearchPage result;
        do {
            int current = page;
            result = call(
                    "event search " + status,
                    () -> rest.get()
                            .uri(uri -> {
                                uri.path("/api/events")
                                        .queryParam("status", status)
                                        .queryParam("page", current)
                                        .queryParam("size", 100);
                                if (from != null) {
                                    uri.queryParam("from", from).queryParam("to", to);
                                }
                                return uri.build();
                            })
                            .retrieve()
                            .body(SearchPage.class),
                    null);
            if (result != null) {
                all.addAll(result.content());
            }
            page++;
        } while (result != null && page < result.totalPages());
        return all;
    }

    /** Drops cached answers for an event (after a 404, and in tests). */
    public void evict(long eventId) {
        events.invalidate(eventId);
        gates.invalidate(eventId);
    }

    private <T> T call(String what, Supplier<T> request, Long eventId) {
        try {
            T body = request.get();
            if (body == null) {
                throw CheckinProblems.eventServiceUnavailable();
            }
            return body;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND && eventId != null) {
                throw NotFoundException.of("Event", eventId);
            }
            log.warn("event-service answered {} for {}", ex.getStatusCode(), what);
            throw CheckinProblems.eventServiceUnavailable();
        } catch (RestClientException ex) {
            log.warn("event-service unreachable for {}: {}", what, ex.getMessage());
            throw CheckinProblems.eventServiceUnavailable();
        }
    }
}

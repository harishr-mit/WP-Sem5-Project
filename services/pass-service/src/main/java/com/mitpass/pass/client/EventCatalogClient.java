package com.mitpass.pass.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.pass.service.PassProblems;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads event details that event-service owns (docs/plans/phase-4.md §4): organizer (ownership checks), venue and map
 * link (ticket), pass colour and the static-QR switch. {@code GET /api/events/{id}} is public, so no token is relayed.
 * Answers are cached for {@code event-cache-ttl} (60 s), so a page of passes costs one call per event at most.
 * A circuit breaker comes in P8; until then a failure without a cached answer is a 503.
 */
public class EventCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(EventCatalogClient.class);

    /** The fields of event-service's {@code EventDetailResponse} that pass-service uses (unknown fields ignored). */
    public record EventInfo(
            Long id,
            String code,
            String title,
            UUID organizerId,
            Instant startsAt,
            Instant endsAt,
            String status,
            Boolean staticQrAllowed,
            String passColor,
            Venue venue) {

        public record Venue(String name, String building, String mapUrl) {}

        /** Older event-service versions don't send the flag: treat it as allowed (the V7 default). */
        public boolean allowsStaticQr() {
            return !Boolean.FALSE.equals(staticQrAllowed);
        }
    }

    private final RestClient rest;
    private final Cache<Long, EventInfo> cache;

    public EventCatalogClient(RestClient rest, Duration ttl, Clock clock) {
        this.rest = rest;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(5_000)
                .ticker(() -> clock.millis() * 1_000_000L)
                .build();
    }

    public EventInfo get(long eventId) {
        EventInfo cached = cache.getIfPresent(eventId);
        if (cached != null) {
            return cached;
        }
        EventInfo fresh = fetch(eventId);
        cache.put(eventId, fresh);
        return fresh;
    }

    /** Drops a cached answer (tests, and after a 404 so a re-created id is seen). */
    public void evict(long eventId) {
        cache.invalidate(eventId);
    }

    private EventInfo fetch(long eventId) {
        try {
            EventInfo info =
                    rest.get().uri("/api/events/{id}", eventId).retrieve().body(EventInfo.class);
            if (info == null) {
                throw PassProblems.eventServiceUnavailable(eventId);
            }
            return info;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw NotFoundException.of("Event", eventId);
            }
            log.warn("event-service answered {} for event {}", ex.getStatusCode(), eventId);
            throw PassProblems.eventServiceUnavailable(eventId);
        } catch (RestClientException ex) {
            log.warn("event-service unreachable for event {}: {}", eventId, ex.getMessage());
            throw PassProblems.eventServiceUnavailable(eventId);
        }
    }
}

package com.mitpass.notification.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mitpass.common.web.paging.PageResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** event-service and pass-service over HTTP (Eureka). */
public final class HttpClients {

    private HttpClients() {}

    /** Catalogue reads go without a token (public); registrations with the SERVICE token on {@code withToken}. */
    public static class Events implements EventDirectory {

        private static final ParameterizedTypeReference<PageResponse<EventInfo>> EVENTS =
                new ParameterizedTypeReference<>() {};
        private static final ParameterizedTypeReference<PageResponse<Recipient>> REGISTRATIONS =
                new ParameterizedTypeReference<>() {};

        private final RestClient publicClient;
        private final RestClient withToken;
        private final Cache<Long, Optional<EventInfo>> cache;

        public Events(RestClient publicClient, RestClient withToken, Duration cacheTtl) {
            this.publicClient = publicClient;
            this.withToken = withToken;
            this.cache = Caffeine.newBuilder()
                    .expireAfterWrite(cacheTtl)
                    .maximumSize(1000)
                    .build();
        }

        @Override
        public Optional<EventInfo> event(long id) {
            return cache.get(id, key -> {
                try {
                    return Optional.ofNullable(publicClient
                            .get()
                            .uri("/api/events/{id}", key)
                            .retrieve()
                            .body(EventInfo.class));
                } catch (HttpClientErrorException.NotFound e) {
                    return Optional.empty();
                }
            });
        }

        @Override
        public void forget(long id) {
            cache.invalidate(id);
        }

        @Override
        public List<Long> startingBetween(Instant from, Instant to) {
            // The catalogue filters by whole days (in its own zone): ask a day wider, apply the exact window here.
            LocalDate firstDay = from.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1);
            LocalDate lastDay = to.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1);
            List<Long> found = new ArrayList<>();
            for (int page = 0; ; page++) {
                int p = page;
                PageResponse<EventInfo> result = publicClient
                        .get()
                        .uri(b -> b.path("/api/events")
                                .queryParam("from", firstDay)
                                .queryParam("to", lastDay)
                                .queryParam("size", 100)
                                .queryParam("page", p)
                                .build())
                        .retrieve()
                        .body(EVENTS);
                if (result == null) {
                    break;
                }
                result.content().stream()
                        .filter(e -> e.startsAt() != null
                                && !e.startsAt().isBefore(from)
                                && e.startsAt().isBefore(to))
                        .forEach(e -> found.add(e.id()));
                if (page + 1 >= result.totalPages()) {
                    break;
                }
            }
            return found;
        }

        @Override
        public List<Recipient> registrations(long eventId, String status) {
            List<Recipient> found = new ArrayList<>();
            for (int page = 0; ; page++) {
                int p = page;
                PageResponse<Recipient> result = withToken
                        .get()
                        .uri(b -> b.path("/api/events/{id}/registrations")
                                .queryParam("status", status)
                                .queryParam("size", 100)
                                .queryParam("page", p)
                                .build(eventId))
                        .retrieve()
                        .body(REGISTRATIONS);
                if (result == null) {
                    break;
                }
                found.addAll(result.content());
                if (page + 1 >= result.totalPages()) {
                    break;
                }
            }
            return found;
        }
    }

    public static class Passes implements PassMedia {

        private final RestClient withToken;

        public Passes(RestClient withToken) {
            this.withToken = withToken;
        }

        @Override
        public Optional<byte[]> staticQrPng(UUID passId) {
            try {
                return Optional.ofNullable(withToken
                        .get()
                        .uri("/api/passes/{id}/qr/static?format=png", passId)
                        .retrieve()
                        .body(byte[].class));
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    return Optional.empty(); // static-qr-disabled: the email links to the wallet instead
                }
                throw e;
            }
        }
    }
}

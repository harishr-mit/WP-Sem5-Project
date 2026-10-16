package com.mitpass.checkin.client;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** pass-service over HTTP with this service's service token ({@link ServiceTokenProvider} on the client). */
public class HttpPassDirectory implements PassDirectory {

    private final RestClient rest;

    public HttpPassDirectory(RestClient rest) {
        this.rest = rest;
    }

    @Override
    public Optional<PassRecord> pass(UUID passId) {
        try {
            return Optional.ofNullable(
                    rest.get().uri("/api/passes/{id}", passId).retrieve().body(PassRecord.class));
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Override
    public PassPage passesOf(long eventId, int page, int size) {
        PassPage result = rest.get()
                .uri("/api/passes?eventId={e}&page={p}&size={s}", eventId, page, size)
                .retrieve()
                .body(PassPage.class);
        return result == null ? new PassPage(null, page, 0) : result;
    }

    @Override
    public RevocationPage revocationsSince(long cursor, int limit) {
        RevocationPage result = rest.get()
                .uri("/api/revocations?since={c}&limit={l}", cursor, limit)
                .retrieve()
                .body(RevocationPage.class);
        return result == null ? new RevocationPage(null, cursor) : result;
    }
}

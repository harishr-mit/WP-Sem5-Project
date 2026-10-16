package com.mitpass.checkin.client;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * pass-service's public keys ({@code /.well-known/pass-jwks.json}), cached by {@code kid} (docs/plans/phase-5.md §4).
 * An unknown {@code kid} (a rotation, D12) triggers one refetch, at most once per {@code jwks-refetch-min-interval}, so a
 * forged {@code kid} can't make us hammer pass-service. Keys already known keep working while pass-service is down.
 */
public class PassJwksCache implements PassKeys {

    private static final Logger log = LoggerFactory.getLogger(PassJwksCache.class);

    private final RestClient rest;
    private final Duration minInterval;
    private final Clock clock;
    private final Map<String, ECKey> keys = new ConcurrentHashMap<>();
    private Instant lastFetch = Instant.MIN;

    public PassJwksCache(RestClient rest, Duration minInterval, Clock clock) {
        this.rest = rest;
        this.minInterval = minInterval;
        this.clock = clock;
    }

    @Override
    public Optional<ECKey> key(String kid) {
        ECKey known = keys.get(kid);
        if (known != null) {
            return Optional.of(known);
        }
        refetchIfAllowed();
        return Optional.ofNullable(keys.get(kid));
    }

    private synchronized void refetchIfAllowed() {
        Instant now = clock.instant();
        if (!lastFetch.equals(Instant.MIN) && now.isBefore(lastFetch.plus(minInterval))) {
            return;
        }
        lastFetch = now;
        try {
            String json =
                    rest.get().uri("/.well-known/pass-jwks.json").retrieve().body(String.class);
            for (JWK jwk : JWKSet.parse(json).getKeys()) {
                if (jwk instanceof ECKey ec && ec.getKeyID() != null) {
                    keys.put(ec.getKeyID(), ec.toPublicJWK());
                }
            }
        } catch (RestClientException | ParseException | NullPointerException ex) {
            log.warn("Could not fetch the pass JWK set: {}", ex.getMessage());
        }
    }
}

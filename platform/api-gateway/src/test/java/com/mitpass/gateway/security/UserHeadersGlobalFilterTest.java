package com.mitpass.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class UserHeadersGlobalFilterTest {

    private final UserHeadersGlobalFilter filter = new UserHeadersGlobalFilter();

    @Test
    void spoofedUserHeadersAreRemovedAndTheRealIdAdded() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("00000000-0000-4000-8000-000000000005")
                .claim("roles", List.of("STUDENT"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(MockServerHttpRequest.get("/api/users/me")
                        .header("X-User-Id", "00000000-0000-4000-8000-000000000001")
                        .header("X-User-Roles", "ADMIN"))
                .principal(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()))
                .build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();

        var headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.get("X-User-Id")).containsExactly("00000000-0000-4000-8000-000000000005");
        assertThat(headers.containsHeader("X-User-Roles")).isFalse();
    }

    @Test
    void anonymousRequestsLoseSpoofedHeadersAndGetNone() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/events").header("X-User-Id", "someone"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();

        assertThat(forwarded.get().getRequest().getHeaders().containsHeader("X-User-Id"))
                .isFalse();
    }

    private static GatewayFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }
}

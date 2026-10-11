package com.mitpass.gateway.security;

import java.util.Locale;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Drops any {@code X-User-*} header a client sent (nobody downstream should ever trust one from outside) and adds
 * {@code X-User-Id} with the token's subject, for logs. Services still authorize from the token itself.
 */
@Component
public class UserHeadersGlobalFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    private static final String PREFIX = "x-user-";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> principal.getName())
                .map(userId -> withHeaders(exchange, userId))
                .defaultIfEmpty(withHeaders(exchange, null))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withHeaders(ServerWebExchange exchange, String userId) {
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.headerNames().stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(PREFIX))
                            .toList()
                            .forEach(headers::remove);
                    if (userId != null) {
                        headers.set(USER_ID, userId);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}

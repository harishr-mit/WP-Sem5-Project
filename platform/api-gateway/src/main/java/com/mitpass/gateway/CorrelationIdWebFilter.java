package com.mitpass.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Stamps every request with an {@code X-Correlation-Id} at the edge (keeping a valid one from the client) and returns it
 * to the caller. Services pick it up in common-web's servlet {@code CorrelationIdFilter}, which can't run here because
 * the gateway is reactive.
 *
 * <p>A {@link WebFilter} (not a gateway {@code GlobalFilter}) at the highest precedence, so it runs before Spring
 * Security: a request rejected at the edge also gets an id, and the 401 body quotes it ({@link #ATTRIBUTE}).
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> headers.set(HEADER, correlationId))
                .build();
        // Set just before commit: replaces the copy echoed by the service instead of sending the header twice.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });
        ServerWebExchange stamped = exchange.mutate().request(request).build();
        stamped.getAttributes().put(ATTRIBUTE, correlationId);
        return chain.filter(stamped);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

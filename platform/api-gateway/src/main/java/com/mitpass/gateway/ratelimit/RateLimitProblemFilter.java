package com.mitpass.gateway.ratelimit;

import com.mitpass.common.web.error.ProblemTypes;
import com.mitpass.gateway.CorrelationIdWebFilter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rate limiter answers 429 with an empty body; this turns it into a ProblemDetail (D6) with {@code Retry-After}:
 * the seconds one request's tokens take to refill, from the limiter's own {@code X-RateLimit-*} headers.
 */
@Component
public class RateLimitProblemFilter implements GlobalFilter, Ordered {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (getStatusCode() != HttpStatus.TOO_MANY_REQUESTS) {
                    return super.setComplete();
                }
                long retryAfter = retryAfterSeconds(getHeaders());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("type", ProblemTypes.TOO_MANY_REQUESTS.toString());
                body.put("title", "Too Many Requests");
                body.put("status", 429);
                body.put("detail", "Too many requests. Try again in " + retryAfter + " s.");
                body.put("instance", exchange.getRequest().getPath().value());
                Object correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);
                if (correlationId != null) {
                    body.put("correlationId", correlationId);
                }
                getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
                byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
                return writeWith(Mono.just(bufferFactory().wrap(bytes)));
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }

    static long retryAfterSeconds(HttpHeaders headers) {
        try {
            long requested = Long.parseLong(headers.getFirst("X-RateLimit-Requested-Tokens"));
            long replenish = Long.parseLong(headers.getFirst("X-RateLimit-Replenish-Rate"));
            return Math.max(1, (requested + replenish - 1) / replenish);
        } catch (RuntimeException missing) {
            return 1;
        }
    }

    /** Wraps the response before any route filter, the rate limiter included, can complete it. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

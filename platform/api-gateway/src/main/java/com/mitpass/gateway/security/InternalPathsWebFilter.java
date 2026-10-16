package com.mitpass.gateway.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Paths a service serves only inside the network (docs/plans/phase-5.md §4b): the edge answers 404 for them, before
 * security and routing, whatever the method or token. Today that is {@code /api/auth/service-token}.
 */
@Component
public class InternalPathsWebFilter implements WebFilter, Ordered {

    /** {@code mitpass.gateway.internal-paths}: path patterns, matched case-insensitively, including anything below them. */
    @ConfigurationProperties("mitpass.gateway")
    public record Properties(
            @DefaultValue("/api/auth/service-token") List<String> internalPaths) {}

    private final List<String> internalPaths;

    public InternalPathsWebFilter(Properties properties) {
        this.internalPaths = properties.internalPaths().stream()
                .map(InternalPathsWebFilter::normalize)
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        String normalized = normalize(path);
        if (internalPaths.stream().noneMatch(p -> normalized.equals(p) || normalized.startsWith(p + "/"))) {
            return chain.filter(exchange);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"type\":\"urn:mitpass:problem:not-found\",\"title\":\"Not Found\",\"status\":404,"
                + "\"detail\":\"No such resource\",\"instance\":\""
                + path.value().replace("\"", "") + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** Decoded segments, lower case, no empty segments: "//API/auth/service%2Dtoken/" and the plain path match alike. */
    static String normalize(PathContainer path) {
        StringBuilder out = new StringBuilder();
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment
                    && !segment.valueToMatch().isEmpty()) {
                out.append('/').append(segment.valueToMatch().toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    private static String normalize(String path) {
        return normalize(PathContainer.parsePath(path));
    }

    /** Right after the correlation id filter, before Spring Security. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}

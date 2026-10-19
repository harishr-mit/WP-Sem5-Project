package com.mitpass.gateway.security;

import com.mitpass.common.web.error.ProblemTypes;
import com.mitpass.gateway.CorrelationIdWebFilter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * A valid token without the role a gateway-owned endpoint needs ({@code /api/admin/**}, ADMIN): {@code 403
 * application/problem+json} with the same body as the services' (D6, D7). Everything else is authorized by the
 * services themselves.
 */
public class ProblemServerAccessDeniedHandler implements ServerAccessDeniedHandler {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        String path = exchange.getRequest().getPath().value();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", ProblemTypes.FORBIDDEN.toString());
        body.put("title", "Forbidden");
        body.put("status", 403);
        body.put("detail", "You don't have the role this needs.");
        body.put("instance", path);
        String correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer =
                response.bufferFactory().wrap(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}

package com.mitpass.gateway.security;

import com.mitpass.common.web.error.ProblemTypes;
import com.mitpass.gateway.CorrelationIdWebFilter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * A missing, forged or expired token stopped at the edge: {@code 401 application/problem+json}, same body as the
 * services' (type, title, status, detail, instance, correlationId), plus {@code WWW-Authenticate: Bearer}.
 */
public class ProblemServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemServerAuthenticationEntryPoint.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        String detail = "Authentication is required. Send an access token as 'Authorization: Bearer <token>'.";
        String wwwAuthenticate = "Bearer";
        if (ex instanceof OAuth2AuthenticationException oauth
                && oauth.getError().getDescription() != null) {
            detail = "The access token was rejected: " + oauth.getError().getDescription();
            wwwAuthenticate = "Bearer error=\"invalid_token\"";
        }
        String path = exchange.getRequest().getPath().value();
        String correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);
        log.info("401 [{}] {} {}: {}", correlationId, exchange.getRequest().getMethod(), path, detail);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", ProblemTypes.UNAUTHORIZED.toString());
        body.put("title", "Unauthorized");
        body.put("status", 401);
        body.put("detail", detail);
        body.put("instance", path);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }

        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate);
        DataBuffer buffer =
                response.bufferFactory().wrap(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}

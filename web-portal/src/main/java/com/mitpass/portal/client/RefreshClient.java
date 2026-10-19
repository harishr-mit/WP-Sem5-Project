package com.mitpass.portal.client;

import com.mitpass.portal.client.ApiModels.TokenResponse;
import com.mitpass.portal.config.PortalProperties;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@code POST /api/auth/refresh} through the gateway, on its own client: no bearer token and no refresh-before-call
 * (that would call itself). Any 4xx answer (unknown, expired, revoked or reused refresh token) means the session is
 * over; a 5xx or no answer is raised as is, so the page shows "back in a moment" instead of logging the user out.
 */
@Component
public class RefreshClient {

    private final RestClient http;

    @Autowired
    public RefreshClient(RestClient.Builder builder, PortalProperties properties) {
        this(builder.clone().requestFactory(timeouts(properties)), properties.gatewayUrl());
    }

    /** For tests: a builder already bound to a mock server. */
    RefreshClient(RestClient.Builder configured, String gatewayUrl) {
        this.http = configured
                .baseUrl(gatewayUrl)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(GatewayClient.CORRELATION_HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(status -> status.is4xxClientError(), (request, response) -> {
                    throw new SessionExpiredException();
                })
                .defaultStatusHandler(status -> status.is5xxServerError(), (request, response) -> {
                    throw new ApiProblemException(503, null, "auth-service is unavailable", null, null);
                })
                .build();
    }

    private static SimpleClientHttpRequestFactory timeouts(PortalProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeouts().connect());
        factory.setReadTimeout(properties.timeouts().read());
        return factory;
    }

    /** New access and refresh tokens; the old refresh token is spent (rotation, D7). */
    public TokenResponse refresh(String refreshToken) {
        return http.post()
                .uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .retrieve()
                .body(TokenResponse.class);
    }
}

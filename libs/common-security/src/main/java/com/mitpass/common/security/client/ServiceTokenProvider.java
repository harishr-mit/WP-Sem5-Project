package com.mitpass.common.security.client;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * This service's own token for background calls (docs/plans/phase-5.md §4b, D7 exception): asked from auth-service with
 * the client id and secret, kept until 30 s before it expires. Also the interceptor that puts it on a request. Shared by
 * every background caller (checkin-service since P5, notification-service since P7).
 */
public class ServiceTokenProvider implements ClientHttpRequestInterceptor {

    private static final Duration MARGIN = Duration.ofSeconds(30);

    record TokenResponse(String accessToken, long expiresIn) {}

    private final RestClient auth;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.MIN;

    public ServiceTokenProvider(RestClient auth, String clientId, String clientSecret, Clock clock) {
        this.auth = auth;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (token == null || !now.isBefore(refreshAt)) {
            TokenResponse response = auth.post()
                    .uri("/api/auth/service-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Credentials(clientId, clientSecret))
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new IllegalStateException("auth-service returned no service token");
            }
            token = response.accessToken();
            refreshAt = now.plusSeconds(response.expiresIn()).minus(MARGIN);
        }
        return token;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(token());
        return execution.execute(request, body);
    }

    private record Credentials(String clientId, String clientSecret) {

        @Override
        public String toString() {
            return "Credentials[clientId=" + clientId + "]";
        }
    }
}

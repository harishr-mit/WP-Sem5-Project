package com.mitpass.common.security.client;

import com.mitpass.common.security.CurrentUser;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Forwards the current request's access token on outgoing {@code RestClient} / {@code RestTemplate} calls, so the
 * downstream service authorizes the original caller (token relay).
 */
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
            CurrentUser.current().ifPresent(user -> request.getHeaders().setBearerAuth(user.tokenValue()));
        }
        return execution.execute(request, body);
    }
}

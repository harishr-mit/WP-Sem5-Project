package com.mitpass.common.security.web;

import com.mitpass.common.web.error.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * {@code 401} for a missing, malformed, forged or expired access token, as {@code application/problem+json}. The
 * {@code WWW-Authenticate: Bearer …} header (RFC 6750) comes from Spring's own bearer entry point.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        bearer.commence(request, response, ex);
        String detail = "Authentication is required. Send an access token as 'Authorization: Bearer <token>'.";
        if (ex instanceof OAuth2AuthenticationException oauth
                && oauth.getError().getDescription() != null) {
            detail = "The access token was rejected: " + oauth.getError().getDescription();
        }
        ProblemResponses.write(
                response,
                ProblemResponses.problem(
                        HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Unauthorized", detail, request),
                request);
    }
}

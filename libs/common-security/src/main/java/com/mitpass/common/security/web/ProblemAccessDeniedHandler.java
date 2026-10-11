package com.mitpass.common.security.web;

import com.mitpass.common.web.error.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

/** {@code 403} from the filter chain (URL rules), as {@code application/problem+json}. */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        bearer.handle(request, response, ex);
        ProblemResponses.write(
                response,
                ProblemResponses.problem(
                        HttpStatus.FORBIDDEN,
                        ProblemTypes.FORBIDDEN,
                        "Forbidden",
                        "You are not allowed to do this",
                        request),
                request);
    }
}

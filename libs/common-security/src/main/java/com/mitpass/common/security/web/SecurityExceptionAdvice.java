package com.mitpass.common.security.web;

import com.mitpass.common.web.error.ProblemTypes;
import com.mitpass.common.web.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Method security ({@code @PreAuthorize}) throws from inside the controller call, so the exception reaches Spring MVC's
 * handlers before Spring Security's filters. Without this advice, common-web's catch-all would turn a denial into a 500.
 * Ordered first, so it wins over {@code GlobalExceptionHandler}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(SecurityExceptionAdvice.class);

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !trustResolver.isAuthenticated(authentication)) {
            return unauthorized(request);
        }
        return respond(
                HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden", "You are not allowed to do this", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> authentication(AuthenticationException ex, HttpServletRequest request) {
        return unauthorized(request);
    }

    private ResponseEntity<ProblemDetail> unauthorized(HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = respond(
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.UNAUTHORIZED,
                "Unauthorized",
                "Authentication is required. Send an access token as 'Authorization: Bearer <token>'.",
                request);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(response.getBody());
    }

    private static ResponseEntity<ProblemDetail> respond(
            HttpStatus status, java.net.URI type, String title, String detail, HttpServletRequest request) {
        log.info("{} uri={}: {}", status.value(), request.getRequestURI(), detail);
        ProblemDetail problem = Problems.create(status, type, title, detail, request.getRequestURI());
        return ResponseEntity.status(status).body(problem);
    }
}

package com.mitpass.portal.web;

import com.mitpass.portal.auth.SessionExpiryFilter;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.SessionExpiredException;
import com.mitpass.portal.web.support.CurrentUserInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.ModelAndView;

/**
 * What a page shows when a backend call fails and the controller didn't handle it (docs/plans/phase-6.md §4): the
 * service's 403/404 become the portal's own 403/404 pages, an unreachable gateway or service the 503 page, any other
 * problem the 500 page with the service's message. Every error page shows the correlation id, never a stack trace. A
 * rejected token ends the session ("log in again"). Spring skips interceptors' {@code postHandle} after an exception,
 * so these pages get the shell attributes (current user, theme, language links) from {@link CurrentUserInterceptor}
 * directly.
 */
@ControllerAdvice
public class PortalAdvice {

    private static final Logger log = LoggerFactory.getLogger(PortalAdvice.class);

    private final CurrentUserInterceptor shell;

    public PortalAdvice(CurrentUserInterceptor shell) {
        this.shell = shell;
    }

    @ExceptionHandler(SessionExpiredException.class)
    public void sessionExpired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SessionExpiryFilter.expire(request, response);
    }

    @ExceptionHandler(ApiProblemException.class)
    public ModelAndView apiProblem(ApiProblemException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.getStatus()) {
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 502, 503, 504 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.warn("Unhandled API problem {} ({}): {}", ex.getStatus(), ex.getType(), ex.getMessage());
        }
        ModelAndView page = new ModelAndView("error/" + status.value(), status);
        page.addObject("detail", status == HttpStatus.INTERNAL_SERVER_ERROR ? ex.getMessage() : null);
        shell.decorate(page, request);
        return page;
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ModelAndView unavailable(ResourceAccessException ex, HttpServletRequest request) {
        log.warn("Backend unreachable: {}", ex.getMessage());
        ModelAndView page = new ModelAndView("error/503", HttpStatus.SERVICE_UNAVAILABLE);
        shell.decorate(page, request);
        return page;
    }
}

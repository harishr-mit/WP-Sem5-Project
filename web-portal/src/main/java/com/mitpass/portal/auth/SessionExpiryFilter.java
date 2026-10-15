package com.mitpass.portal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends the session once the access token has expired, and sends the user to {@code /login?expired}. P3 slice
 * shortcut (docs/DECISIONS.md D9): the user logs in again; P6 uses the refresh token instead.
 */
public class SessionExpiryFilter extends OncePerRequestFilter {

    private final Clock clock;

    public SessionExpiryFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof PortalUser user
                && user.isExpired(clock.instant())) {
            expire(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Also used when the gateway answers 401 in the middle of a page. */
    public static void expire(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect(request.getContextPath() + "/login?expired");
    }
}

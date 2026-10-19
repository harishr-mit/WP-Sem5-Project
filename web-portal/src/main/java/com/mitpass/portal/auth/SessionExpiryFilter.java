package com.mitpass.portal.auth;

import com.mitpass.portal.client.SessionExpiredException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps a logged-in session's access token fresh before the request reaches a page (docs/plans/phase-6.md §7): a
 * token about to expire is refreshed with the refresh token. Only when that is impossible (auth-service refused the
 * refresh token: expired, revoked, reused) does the session end, with "log in again" ({@code /login?expired}). If
 * auth-service can't be reached right now, the request goes on: public pages still work, and a page that needs the
 * backend shows the "back in a moment" page.
 */
public class SessionExpiryFilter extends OncePerRequestFilter {

    /** HTMX marks its requests with this header; they get {@code HX-Redirect} instead of a 302 to follow silently. */
    public static final String HX_REQUEST = "HX-Request";

    private final TokenRefresher refresher;

    public SessionExpiryFilter(TokenRefresher refresher) {
        this.refresher = refresher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof PortalUser user
                && refresher.needsRefresh(user)) {
            try {
                refresher.ensureFresh();
            } catch (SessionExpiredException ex) {
                expire(request, response);
                return;
            } catch (ResourceAccessException ex) {
                logger.warn("Couldn't refresh the access token (auth-service unreachable): " + ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }

    /** Ends the session and sends the user to log in again. Also used when the gateway answers 401 mid-page. */
    public static void expire(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        redirect(request, response, "/login?expired");
    }

    /** A redirect that also works for HTMX requests (an XHR would follow a 302 and swap the login page in place). */
    public static void redirect(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException {
        String target = request.getContextPath() + path;
        if ("true".equals(request.getHeader(HX_REQUEST))) {
            response.setHeader("HX-Redirect", target);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        response.sendRedirect(target);
    }
}

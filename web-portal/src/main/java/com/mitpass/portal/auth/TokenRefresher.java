package com.mitpass.portal.auth;

import com.mitpass.portal.client.AccessTokenSource;
import com.mitpass.portal.client.ApiModels.TokenResponse;
import com.mitpass.portal.client.RefreshClient;
import com.mitpass.portal.client.SessionExpiredException;
import com.mitpass.portal.config.PortalProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.WebUtils;

/**
 * Keeps the session's access token fresh (docs/plans/phase-6.md §7; D9 row "log in again after 15 min" replaced).
 *
 * <p>Before a gateway call, a token that expires within {@code mitpass.portal.refresh-ahead} is swapped for a new one
 * with the refresh token. auth-service rotates refresh tokens and treats a reused one as stolen, revoking the whole
 * login (D7). Several requests of one session can arrive at the same moment (a page, its QR image, an HTMX poll), so
 * the refresh runs under the session's mutex, and after taking it the user is read <em>again from the session</em>:
 * whoever waited finds the token another request has just refreshed and uses that, instead of spending the old
 * refresh token a second time.
 *
 * <p>The new tokens go into the {@code SecurityContext} of this request and are saved to the session explicitly
 * (Spring Security 6+ doesn't save a changed context on its own).
 */
@Component
public class TokenRefresher implements AccessTokenSource {

    private static final Logger log = LoggerFactory.getLogger(TokenRefresher.class);

    private final RefreshClient refreshClient;
    private final PortalProperties properties;
    private final Clock clock;
    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public TokenRefresher(RefreshClient refreshClient, PortalProperties properties, Clock clock) {
        this.refreshClient = refreshClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String current() {
        PortalUser user = currentUser();
        if (user == null) {
            return null;
        }
        return needsRefresh(user) ? refresh(user, false).accessToken() : user.accessToken();
    }

    @Override
    public String afterRejection(String rejected) {
        PortalUser user = currentUser();
        if (user == null || !rejected.equals(user.accessToken())) {
            // The holder already has a different token: another refresh happened meanwhile; use it.
            return user == null ? null : user.accessToken();
        }
        return refresh(user, true).accessToken();
    }

    /**
     * True when the token is expired or expires within the refresh-ahead window. The window is at most a quarter of the
     * token's lifetime: with a 60 s window and 1 min tokens (a demo setting) every request would refresh otherwise.
     */
    public boolean needsRefresh(PortalUser user) {
        java.time.Duration ahead = properties.refreshAhead();
        java.time.Duration quarter =
                user.tokenLifetime().map(l -> l.dividedBy(4)).orElse(ahead);
        if (quarter.compareTo(ahead) < 0) {
            ahead = quarter;
        }
        return !clock.instant().plus(ahead).isBefore(user.expiresAt());
    }

    /**
     * Refreshes if needed and returns the user to work with. Throws {@link SessionExpiredException} when the session
     * can't continue (no session, or auth-service refused the refresh token).
     */
    public PortalUser ensureFresh() {
        PortalUser user = currentUser();
        if (user == null || !needsRefresh(user)) {
            return user;
        }
        return refresh(user, false);
    }

    private PortalUser refresh(PortalUser seen, boolean force) {
        ServletRequestAttributes attributes = requestAttributes();
        if (attributes == null) {
            return seen; // not in a web request: nothing to save the new token into
        }
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new SessionExpiredException();
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            PortalUser stored = userIn(session);
            if (stored != null && !stored.accessToken().equals(seen.accessToken())) {
                // Another request refreshed while we waited for the lock.
                install(stored, request, response, false);
                return stored;
            }
            PortalUser base = stored != null ? stored : seen;
            if (!force && !needsRefresh(base)) {
                return base;
            }
            TokenResponse tokens = refreshClient.refresh(base.refreshToken());
            PortalUser fresh = GatewayAuthenticationProvider.toUser(tokens, clock.instant());
            install(fresh, request, response, true);
            log.debug("Access token refreshed for user {}", fresh.id());
            return fresh;
        }
    }

    private void install(PortalUser user, HttpServletRequest request, HttpServletResponse response, boolean save) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication(user));
        SecurityContextHolder.setContext(context);
        if (save) {
            repository.saveContext(context, request, response);
        }
    }

    static Authentication authentication(PortalUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                user,
                null,
                user.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList());
    }

    private static PortalUser userIn(HttpSession session) {
        Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (stored instanceof SecurityContext context
                && context.getAuthentication() != null
                && context.getAuthentication().getPrincipal() instanceof PortalUser user) {
            return user;
        }
        return null;
    }

    private static PortalUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof PortalUser user ? user : null;
    }

    private static ServletRequestAttributes requestAttributes() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes
                : null;
    }
}

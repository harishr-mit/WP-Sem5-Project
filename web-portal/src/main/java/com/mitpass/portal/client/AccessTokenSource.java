package com.mitpass.portal.client;

import com.mitpass.portal.auth.PortalUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Where {@link GatewayClient} gets the access token it sends (docs/plans/phase-6.md §7). The portal's implementation
 * is {@link com.mitpass.portal.auth.TokenRefresher}, which refreshes a token that is about to expire.
 */
public interface AccessTokenSource {

    /** The token to send now, refreshed first if it is about to expire; null when nobody is logged in. */
    String current();

    /**
     * The gateway answered 401 to {@code rejected}. Returns a newer token to retry once with, or null when there is
     * none (the caller then treats the session as over).
     */
    String afterRejection(String rejected);

    /** The logged-in user's token as it is, never refreshed (tests, and anything outside a web request). */
    static AccessTokenSource fromSecurityContext() {
        return new AccessTokenSource() {
            @Override
            public String current() {
                Authentication authentication =
                        SecurityContextHolder.getContext().getAuthentication();
                return authentication != null && authentication.getPrincipal() instanceof PortalUser user
                        ? user.accessToken()
                        : null;
            }

            @Override
            public String afterRejection(String rejected) {
                return null;
            }
        };
    }
}

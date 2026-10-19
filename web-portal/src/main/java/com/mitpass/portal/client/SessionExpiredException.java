package com.mitpass.portal.client;

/**
 * The session can't go on: the gateway rejected the access token even after a refresh, or auth-service refused the
 * refresh token (expired, revoked, reused). The user logs in again (docs/plans/phase-6.md §7).
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException() {
        super("Your session has expired. Please log in again.");
    }
}

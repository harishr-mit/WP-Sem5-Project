package com.mitpass.portal.client;

/**
 * The gateway rejected the session's access token (401). In the P3 slice the user logs in again; P6 refreshes the
 * token automatically (docs/DECISIONS.md D9).
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException() {
        super("Your session has expired. Please log in again.");
    }
}

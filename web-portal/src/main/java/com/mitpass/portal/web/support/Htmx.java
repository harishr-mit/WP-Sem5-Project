package com.mitpass.portal.web.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The few HTMX conventions the portal uses (docs/plans/phase-6.md §4). Every HTMX endpoint is an ordinary MVC
 * endpoint: it answers a fragment to HTMX and the full page to everyone else.
 */
public final class Htmx {

    /** HTMX stops polling an element when the answer has this status. */
    public static final int STOP_POLLING = 286;

    private Htmx() {}

    /**
     * True for an HTMX request that wants a fragment. A history restore (the back button after an
     * {@code hx-push-url}) wants the whole page.
     */
    public static boolean isPartial(HttpServletRequest request) {
        return "true".equals(request.getHeader("HX-Request"))
                && !"true".equals(request.getHeader("HX-History-Restore-Request"));
    }
}

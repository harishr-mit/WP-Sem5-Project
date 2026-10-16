package com.mitpass.portal.web;

import org.springframework.stereotype.Component;

/**
 * What the organizer's lifecycle buttons say and which of them need a second look (REVIEW-LATER RL8, RL9; owner
 * 2026-10-16). The API keeps its state names; only the portal's wording changes. Used in templates as
 * {@code ${@actions.label(e.status(), to)}}. P6 moves the wording into the i18n message bundles.
 */
@Component("actions")
public class LifecycleActions {

    /** A direct verb for the move from {@code from} to {@code to}. */
    public String label(String from, String to) {
        return switch (to) {
            case "PUBLISHED" -> "Publish";
            case "REGISTRATION_OPEN" -> "Open registrations";
            case "REGISTRATION_CLOSED" -> isUndoStart(from, to) ? "Undo start" : "Close registrations";
            case "COMPLETED" -> "End event";
            case "CANCELLED" -> "Cancel event";
            default -> to;
        };
    }

    /** Moves an accidental click must not trigger: they stop the event or can't be taken back (RL9). */
    public boolean isCritical(String from, String to) {
        return "CANCELLED".equals(to) || "COMPLETED".equals(to) || isUndoStart(from, to);
    }

    /** Cancelling is the most destructive move: the organizer types the event code to confirm. */
    public boolean needsCode(String to) {
        return "CANCELLED".equals(to);
    }

    /** What happens, shown on the confirmation page. */
    public String consequence(String from, String to) {
        if ("CANCELLED".equals(to)) {
            return "The event is cancelled for everyone. Registration stops and every valid pass is revoked at once."
                    + " This can't be undone.";
        }
        if ("COMPLETED".equals(to)) {
            return "The event ends now: the gates close and every valid pass expires. This can't be undone.";
        }
        if (isUndoStart(from, to)) {
            return "The event goes back to registration closed and the gates close. It goes live again at the next"
                    + " scheduler run unless you move its start time later.";
        }
        return "";
    }

    private static boolean isUndoStart(String from, String to) {
        return "LIVE".equals(from) && "REGISTRATION_CLOSED".equals(to);
    }
}

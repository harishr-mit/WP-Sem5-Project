package com.mitpass.portal.web;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * What the organizer's lifecycle buttons say and which of them need a second look (REVIEW-LATER RL8, RL9; owner
 * 2026-10-16). The API keeps its state names; the wording comes from the message bundles ({@code action.*},
 * {@code consequence.*}; P6, D9 row). Used in templates as {@code ${@actions.label(e.status(), to)}}.
 */
@Component("actions")
public class LifecycleActions {

    private final MessageSource messages;

    public LifecycleActions(MessageSource messages) {
        this.messages = messages;
    }

    /** A direct verb for the move from {@code from} to {@code to}. */
    public String label(String from, String to) {
        return msg("action." + key(from, to), to);
    }

    /** Moves an accidental click must not trigger: they stop the event or can't be taken back (RL9). */
    public boolean isCritical(String from, String to) {
        return "CANCELLED".equals(to) || "COMPLETED".equals(to) || isUndoStart(from, to);
    }

    /** Cancelling is the most destructive move: the organizer types the event code to confirm. */
    public boolean needsCode(String to) {
        return "CANCELLED".equals(to);
    }

    /** What happens, shown on the confirmation page or dialog. */
    public String consequence(String from, String to) {
        return isCritical(from, to) ? msg("consequence." + key(from, to), "") : "";
    }

    private static String key(String from, String to) {
        return isUndoStart(from, to) ? "UNDO_START" : to;
    }

    private static boolean isUndoStart(String from, String to) {
        return "LIVE".equals(from) && "REGISTRATION_CLOSED".equals(to);
    }

    private String msg(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}

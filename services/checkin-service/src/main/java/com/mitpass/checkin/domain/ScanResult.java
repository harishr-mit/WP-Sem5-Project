package com.mitpass.checkin.domain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Every answer a gate can get (docs/plans/phase-5.md §5): HTTP status, colour and the message the volunteer sees.
 * Messages live here only, so P6's i18n bundles can take them over. {@code {name}}-style placeholders are filled from
 * the scan's details; a missing one becomes "?".
 */
public enum ScanResult {
    ACCEPTED(HttpStatus.OK, Color.GREEN, "Welcome, {name}."),
    CHECKED_OUT(HttpStatus.OK, Color.GREEN, "{name} checked out."),
    ALREADY_CHECKED_IN(HttpStatus.CONFLICT, Color.AMBER, "Already checked in at {time} at {gate}."),
    REENTRY_NOT_ALLOWED(HttpStatus.CONFLICT, Color.RED, "This event doesn't allow re-entry."),
    NOT_CHECKED_IN(HttpStatus.CONFLICT, Color.AMBER, "Not checked in, so can't check out."),
    REVOKED(HttpStatus.CONFLICT, Color.RED, "This pass was revoked."),
    NOT_YET_OPEN(HttpStatus.CONFLICT, Color.AMBER, "Doors aren't open yet (opens {time})."),
    EVENT_CLOSED(HttpStatus.CONFLICT, Color.RED, "This event has ended."),
    WRONG_EVENT(HttpStatus.CONFLICT, Color.RED, "This pass is for {event}."),
    WRONG_GATE(HttpStatus.CONFLICT, Color.AMBER, "You're assigned to {gate}, not this one."),
    EXPIRED_TOKEN(HttpStatus.UNPROCESSABLE_CONTENT, Color.RED, "Code expired: ask for the live code."),
    INVALID_SIGNATURE(HttpStatus.UNPROCESSABLE_CONTENT, Color.RED, "Not a valid MITPass code."),
    INVALID_PASS_NUMBER(HttpStatus.UNPROCESSABLE_CONTENT, Color.RED, "That pass number has a typo (check digit)."),
    UNKNOWN_PASS(HttpStatus.NOT_FOUND, Color.AMBER, "Pass not found here yet: ask the organizer."),
    UNAUTHORIZED_SCANNER(HttpStatus.FORBIDDEN, Color.RED, "You aren't assigned to this event."),
    /** P7 offline sync: the pass was already checked in before this queued scan reached the server (Q2). */
    DUPLICATE_OFFLINE(HttpStatus.CONFLICT, Color.RED, "Already checked in: this offline scan came later."),
    /** P7 offline sync: the phone's time for the scan is too old or in the future (Q12). */
    REJECTED_OFFLINE_CLOCK(HttpStatus.UNPROCESSABLE_CONTENT, Color.AMBER, "The phone's clock looks wrong: scan again.");

    /** Green: let them in (or out). Amber: a person should look. Red: refuse. */
    public enum Color {
        GREEN,
        AMBER,
        RED
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final HttpStatus status;
    private final Color color;
    private final String template;

    ScanResult(HttpStatus status, Color color, String template) {
        this.status = status;
        this.color = color;
        this.template = template;
    }

    public HttpStatus status() {
        return status;
    }

    public Color color() {
        return color;
    }

    public boolean accepted() {
        return status == HttpStatus.OK;
    }

    /** The problem type slug of a rejection: {@code scan/already-checked-in}. */
    public String problemSlug() {
        return "scan/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String message(Map<String, String> details) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = details.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "?" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

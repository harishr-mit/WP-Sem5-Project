package com.mitpass.checkin.web;

import com.mitpass.checkin.service.ScanOutcome;
import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A refused scan as a D6 ProblemDetail: type {@code urn:mitpass:problem:scan/<code>}, the result's HTTP status, and
 * the same fields as an accepted scan's body as extension members ({@code result}, {@code message}, {@code color},
 * {@code scanId}, {@code attendee}, …), so a scanner reads both the same way.
 */
public class ScanRejectedException extends ProblemException {

    public ScanRejectedException(ScanOutcome outcome) {
        super(
                outcome.result().status(),
                ProblemTypes.of(outcome.result().problemSlug()),
                title(outcome),
                outcome.message(),
                Map.of(),
                properties(outcome));
    }

    private static String title(ScanOutcome outcome) {
        String words = outcome.result().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static Map<String, Object> properties(ScanOutcome outcome) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("result", outcome.result().name());
        properties.put("message", outcome.message());
        properties.put("color", outcome.result().color().name());
        properties.put("scanId", outcome.scanId().toString());
        if (outcome.attendee() != null) {
            properties.put("attendee", outcome.attendee());
        }
        if (outcome.eventCode() != null) {
            properties.put("eventCode", outcome.eventCode());
        }
        if (outcome.gateCode() != null) {
            properties.put("gateCode", outcome.gateCode());
        }
        if (outcome.checkedInAt() != null) {
            properties.put("checkedInAt", outcome.checkedInAt().toString());
        }
        return properties;
    }
}

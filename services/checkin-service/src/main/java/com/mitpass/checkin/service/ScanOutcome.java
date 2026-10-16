package com.mitpass.checkin.service;

import com.mitpass.checkin.domain.ScanResult;
import java.time.Instant;
import java.util.UUID;

/**
 * The answer to one scan, accepted or not (docs/plans/phase-5.md §5, §7). The controller turns a rejection into a
 * ProblemDetail with the same fields; P7's batch endpoint will return these as they are.
 *
 * @param attendee null when the pipeline stopped before the pass was known
 * @param checkedInAt the first check-in of this pass for this session (ACCEPTED, ALREADY_CHECKED_IN)
 * @param reentry ACCEPTED as a re-entry (no new attendance)
 */
public record ScanOutcome(
        UUID scanId,
        ScanResult result,
        String message,
        Long eventId,
        String eventCode,
        String gateCode,
        Long sessionId,
        String sessionTitle,
        Attendee attendee,
        Instant checkedInAt,
        boolean reentry) {

    public record Attendee(UUID passId, String passNumber, String holderName) {}

    public boolean accepted() {
        return result.accepted();
    }
}

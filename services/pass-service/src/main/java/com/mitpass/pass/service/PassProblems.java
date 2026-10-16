package com.mitpass.pass.service;

import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import com.mitpass.pass.domain.PassStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** The pass problem types of docs/plans/phase-4.md §7. */
public final class PassProblems {

    public static final String CODE_LOCKED = "code-locked";
    public static final String STATIC_QR_DISABLED = "static-qr-disabled";
    public static final String PASS_NOT_VALID = "pass-not-valid";
    public static final String EVENT_SERVICE_UNAVAILABLE = "event-service-unavailable";

    private PassProblems() {}

    public static ProblemException codeLocked(UUID passId, Instant unlocksAt) {
        return new ProblemException(
                HttpStatus.CONFLICT,
                ProblemTypes.of(CODE_LOCKED),
                "Live code not unlocked yet",
                "The live code for pass %s appears at %s (2 hours before doors open), so it can't be passed around early."
                        .formatted(passId, unlocksAt),
                Map.of(),
                Map.of("unlocksAt", unlocksAt.toString()));
    }

    public static ProblemException staticQrDisabled(long eventId) {
        return new ProblemException(
                HttpStatus.CONFLICT,
                ProblemTypes.of(STATIC_QR_DISABLED),
                "Static QR disabled",
                "Event %d allows only the live code: open the pass in My passes at the gate.".formatted(eventId));
    }

    public static ProblemException passNotValid(UUID passId, PassStatus status) {
        return new ProblemException(
                HttpStatus.CONFLICT,
                ProblemTypes.of(PASS_NOT_VALID),
                "Pass not valid",
                "Pass %s is %s and can't be used any more.".formatted(passId, status),
                Map.of(),
                Map.of("status", status.name()));
    }

    public static ProblemException eventServiceUnavailable(long eventId) {
        return new ProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.of(EVENT_SERVICE_UNAVAILABLE),
                "Event details unavailable",
                "Could not read event %d from event-service. Try again in a moment.".formatted(eventId));
    }
}

package com.mitpass.event.service;

import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The registration problem types of docs/plans/phase-3.md §7. */
public final class RegistrationProblems {

    public static final String ALREADY_REGISTERED = "already-registered";
    public static final String REGISTRATION_CLOSED = "registration-closed";
    public static final String NOT_ELIGIBLE = "not-eligible";

    private RegistrationProblems() {}

    static ProblemException alreadyRegistered(long eventId) {
        return new ProblemException(
                HttpStatus.CONFLICT,
                ProblemTypes.of(ALREADY_REGISTERED),
                "Already registered",
                "You already have an active registration for event %d. Cancel it first to register again."
                        .formatted(eventId));
    }

    static ProblemException registrationClosed(String detail) {
        return new ProblemException(
                HttpStatus.CONFLICT, ProblemTypes.of(REGISTRATION_CLOSED), "Registration closed", detail);
    }

    static ProblemException notEligible(List<String> reasons) {
        return new ProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemTypes.of(NOT_ELIGIBLE),
                "Not eligible",
                String.join("; ", reasons),
                Map.of(),
                Map.of("reasons", reasons));
    }
}

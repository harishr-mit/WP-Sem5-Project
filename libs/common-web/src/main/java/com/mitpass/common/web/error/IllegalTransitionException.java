package com.mitpass.common.web.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A state machine refused a move ({@code docs/DECISIONS.md} D10). Mapped to {@code 409 illegal-transition}; the body
 * carries {@code currentState}, {@code requestedState} and {@code allowedStates}, so a client can show what is possible
 * instead.
 */
public class IllegalTransitionException extends ProblemException {

    private final String currentState;
    private final String requestedState;
    private final List<String> allowedStates;

    public IllegalTransitionException(String currentState, String requestedState, List<String> allowedStates) {
        super(
                HttpStatus.CONFLICT,
                ProblemTypes.ILLEGAL_TRANSITION,
                "Illegal state transition",
                message(currentState, requestedState, allowedStates),
                Map.of(),
                Map.of(
                        "currentState", currentState,
                        "requestedState", requestedState,
                        "allowedStates", List.copyOf(allowedStates)));
        this.currentState = currentState;
        this.requestedState = requestedState;
        this.allowedStates = List.copyOf(allowedStates);
    }

    public String getCurrentState() {
        return currentState;
    }

    public String getRequestedState() {
        return requestedState;
    }

    public List<String> getAllowedStates() {
        return allowedStates;
    }

    private static String message(String current, String requested, List<String> allowed) {
        String next = allowed.isEmpty()
                ? "%s is final; no further moves are allowed".formatted(current)
                : "allowed from %s: %s".formatted(current, String.join(", ", allowed));
        return "Cannot move from %s to %s (%s)".formatted(current, requested, next);
    }
}

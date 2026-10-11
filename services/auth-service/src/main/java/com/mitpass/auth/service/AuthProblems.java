package com.mitpass.auth.service;

import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** The auth-specific errors (docs/plans/phase-2.md §4 "Error statuses"). */
public final class AuthProblems {

    private AuthProblems() {}

    /** Same answer for an unknown email and a wrong password, so the response doesn't reveal which accounts exist. */
    public static ProblemException invalidCredentials() {
        return new ProblemException(
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.of("invalid-credentials"),
                "Login failed",
                "Email or password is wrong");
    }

    public static ProblemException emailNotVerified() {
        return new ProblemException(
                HttpStatus.FORBIDDEN,
                ProblemTypes.of("email-not-verified"),
                "Email not verified",
                "Verify your email address first: open the link we sent you, or ask for a new one.");
    }

    public static ProblemException accountDisabled() {
        return new ProblemException(
                HttpStatus.FORBIDDEN,
                ProblemTypes.of("account-disabled"),
                "Account disabled",
                "This account has been disabled");
    }

    public static ProblemException accountLocked(Instant lockedUntil, Instant now) {
        long seconds = Math.max(1, Duration.between(now, lockedUntil).toSeconds());
        return new ProblemException(
                HttpStatus.LOCKED,
                ProblemTypes.of("account-locked"),
                "Account locked",
                "Too many failed logins. Try again in %d minutes, or ask an admin to unlock the account."
                        .formatted(Math.max(1, (seconds + 59) / 60)),
                Map.of(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
    }

    public static ProblemException invalidVerificationToken() {
        return new ProblemException(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.of("invalid-token"),
                "Invalid link",
                "This verification link is invalid or has expired. Ask for a new one.");
    }

    public static ProblemException invalidRefreshToken() {
        return new ProblemException(
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.of("invalid-refresh-token"),
                "Invalid refresh token",
                "The refresh token is unknown, expired or revoked. Log in again.");
    }

    public static ProblemException refreshTokenReused() {
        return new ProblemException(
                HttpStatus.UNAUTHORIZED,
                ProblemTypes.of("refresh-token-reused"),
                "Refresh token reused",
                "This refresh token was already used, so it may have been stolen. Every session of this login has"
                        + " been ended; log in again.");
    }
}

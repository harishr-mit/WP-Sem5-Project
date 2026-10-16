package com.mitpass.checkin.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * checkin-service's read-only copy of a pass (D14): what a scan needs without asking pass-service. Number and holder
 * can be missing for a pass whose revocation arrived before its details.
 */
public record PassRef(
        UUID passId,
        String passNumber,
        long eventId,
        UUID registrationId,
        UUID userId,
        String holderName,
        String holderEmail,
        String status,
        Instant issuedAt,
        Instant revokedAt,
        String revokeReason) {

    public static final String VALID = "VALID";
    public static final String REVOKED = "REVOKED";

    public boolean revoked() {
        return REVOKED.equals(status);
    }

    /** Known well enough to admit someone: the details have arrived, not just a revocation. */
    public boolean complete() {
        return passNumber != null && holderName != null;
    }
}

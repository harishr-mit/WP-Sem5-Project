package com.mitpass.checkin.client;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * pass-service, the owner of passes (D14), as the reconciler sees it. Called with this service's own service token
 * (§4b), never a user's: the jobs run without a caller.
 */
public interface PassDirectory {

    /** {@code GET /api/passes/{id}}; empty if pass-service doesn't know it. Throws if pass-service is unreachable. */
    Optional<PassRecord> pass(UUID passId);

    /** {@code GET /api/passes?eventId=&page=&size=}, newest first. */
    PassPage passesOf(long eventId, int page, int size);

    /** {@code GET /api/revocations?since=&limit=}: the cursor feed built in P4. */
    RevocationPage revocationsSince(long cursor, int limit);

    /** The fields of pass-service's {@code PassResponse} that the copy keeps. */
    record PassRecord(
            UUID id,
            String passNumber,
            String status,
            UUID registrationId,
            Long eventId,
            UUID userId,
            String holderName,
            String holderEmail,
            Instant issuedAt,
            Instant revokedAt,
            String revokeReason) {

        public boolean revoked() {
            return "REVOKED".equals(status);
        }
    }

    record PassPage(List<PassRecord> content, int page, int totalPages) {

        public PassPage {
            content = content == null ? List.of() : content;
        }
    }

    record RevocationItem(long seq, UUID passId, long eventId, Instant revokedAt, String reason) {}

    record RevocationPage(List<RevocationItem> items, long nextSince) {

        public RevocationPage {
            items = items == null ? List.of() : items;
        }
    }
}

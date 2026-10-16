package com.mitpass.pass.web.dto;

import com.mitpass.pass.domain.PassChange;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.domain.Revocation;
import com.mitpass.pass.domain.RevokeReason;
import com.mitpass.pass.domain.TransitionTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Small request and response bodies of the pass API (docs/plans/phase-4.md §7). */
public final class PassDtos {

    private PassDtos() {}

    public record PassChangeResponse(
            PassStatus fromStatus,
            PassStatus toStatus,
            Instant changedAt,
            String changedBy,
            TransitionTrigger trigger,
            String reason) {

        public static PassChangeResponse from(PassChange change) {
            return new PassChangeResponse(
                    change.getFromStatus(),
                    change.getToStatus(),
                    change.getChangedAt(),
                    change.getChangedBy(),
                    change.getTrigger(),
                    change.getReason());
        }
    }

    /** A token for clients that draw the QR themselves (P6 countdown ring, P7 offline scanner tests). */
    public record TokenResponse(
            @Schema(description = "Compact ES256 JWS; verify with /.well-known/pass-jwks.json")
            String token,

            String kind,
            Instant issuedAt,
            Instant expiresAt,

            @Schema(description = "Fetch a new live token after this many seconds")
            Long refreshAfterSeconds) {}

    public record RevokeRequest(
            @Schema(example = "Duplicate registration under another email") @Size(max = 200)
            String note) {}

    public record RevocationEntry(long seq, UUID passId, long eventId, Instant revokedAt, RevokeReason reason) {

        public static RevocationEntry from(Revocation revocation) {
            return new RevocationEntry(
                    revocation.getSeq(),
                    revocation.getPassId(),
                    revocation.getEventId(),
                    revocation.getRevokedAt(),
                    revocation.getReason());
        }
    }

    /** One page of the revocation feed; pass {@code nextSince} as {@code since} next time. */
    public record RevocationFeed(List<RevocationEntry> items, long nextSince) {}

    public record KeyRotationResponse(String kid) {}
}

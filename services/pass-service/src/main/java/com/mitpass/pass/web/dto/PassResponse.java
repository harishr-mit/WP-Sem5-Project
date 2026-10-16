package com.mitpass.pass.web.dto;

import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.domain.RevokeReason;
import java.time.Instant;
import java.util.UUID;

/**
 * A pass card. {@code codeUnlocksAt} is when the live code appears (ISSUED → ACTIVE). Contract: fields are only added.
 */
public record PassResponse(
        UUID id,
        String passNumber,
        PassStatus status,
        UUID registrationId,
        Long eventId,
        String eventCode,
        String eventTitle,
        Instant startsAt,
        Instant endsAt,
        UUID userId,
        String holderName,
        String holderEmail,
        Instant issuedAt,
        Instant codeUnlocksAt,
        Instant activatedAt,
        Instant expiredAt,
        Instant revokedAt,
        RevokeReason revokeReason,
        String revokeNote) {

    public static PassResponse from(Pass pass, Instant codeUnlocksAt) {
        return new PassResponse(
                pass.getId(),
                pass.getPassNumber(),
                pass.getStatus(),
                pass.getRegistrationId(),
                pass.getEvent().getId(),
                pass.getEvent().getCode(),
                pass.getEvent().getTitle(),
                pass.getEvent().getStartsAt(),
                pass.getEvent().getEndsAt(),
                pass.getUserId(),
                pass.getHolderName(),
                pass.getHolderEmail(),
                pass.getIssuedAt(),
                codeUnlocksAt,
                pass.getActivatedAt(),
                pass.getExpiredAt(),
                pass.getRevokedAt(),
                pass.getRevokeReason(),
                pass.getRevokeNote());
    }
}

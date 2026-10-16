package com.mitpass.common.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * checkin-service recorded the first check-in of a pass for one session (docs/plans/phase-5.md §4). Re-entries and
 * check-outs don't publish it. pass-service records {@code checkedInAt} / {@code usedAt}; the live dashboard (P7) and
 * notification-service build on it. {@code sessionId} 0 = the whole event (no sessions). {@code method}: QR_LIVE,
 * QR_STATIC or MANUAL. {@code singleEntry}: the event doesn't allow re-entry. Contract: fields are only ever added.
 */
@EventType("attendance.marked")
public record AttendanceMarked(
        UUID attendanceId,
        UUID passId,
        String passNumber,
        UUID registrationId,
        long eventId,
        long sessionId,
        UUID userId,
        Long gateId,
        String gateCode,
        UUID volunteerId,
        String method,
        Instant checkedInAt,
        Instant scannedAt,
        boolean singleEntry) {}

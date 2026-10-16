package com.mitpass.checkin.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Report bodies (docs/plans/phase-5.md §7). Contract: fields are only added. */
public final class AttendanceDtos {

    private AttendanceDtos() {}

    /**
     * @param validPasses passes that can still be used (not revoked)
     * @param checkedIn distinct passes checked in at least once
     * @param percent {@code checkedIn / validPasses}, one decimal; 0 when there are no passes
     * @param insideNow presence IN right now
     */
    public record AttendanceSummary(
            long eventId,
            String eventCode,
            String eventTitle,
            long validPasses,
            long checkedIn,
            double percent,
            long insideNow,
            List<SessionCount> sessions,
            List<GateCount> gates,
            Instant lastScanAt) {}

    /** {@code sessionId} 0 = the whole event (no sessions). */
    public record SessionCount(long sessionId, String title, long checkedIn, double percent) {}

    public record GateCount(String gateCode, long checkedIn) {}

    public record AttendeeRow(
            UUID passId,
            String passNumber,
            String holderName,
            String holderEmail,
            String passStatus,
            int sessionsAttended,
            Instant firstCheckIn,
            String lastGate,
            String presence) {}

    public record CertificateRow(
            String passNumber, String holderName, String holderEmail, int sessionsAttended, int required) {}

    /** A student's own attendance per event. {@code certificateEligible} = attended at least the required sessions. */
    public record MyAttendance(
            long eventId,
            String eventCode,
            String eventTitle,
            String passNumber,
            int sessionsAttended,
            Integer sessionsTotal,
            Integer sessionsRequired,
            Boolean certificateEligible,
            Instant firstCheckIn) {}

    public record SyncResponse(long eventId, int passes, int revoked) {}
}

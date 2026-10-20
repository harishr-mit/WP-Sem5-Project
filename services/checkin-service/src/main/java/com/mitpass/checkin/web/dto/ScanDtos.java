package com.mitpass.checkin.web.dto;

import com.mitpass.checkin.domain.PresenceState;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.service.ScanOutcome;
import com.mitpass.checkin.service.ScanOutcome.Attendee;
import com.mitpass.checkin.service.ScanService.Lookup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the scan API (docs/plans/phase-5.md §7). */
public final class ScanDtos {

    private ScanDtos() {}

    public record ScanRequest(
            @Schema(description = "The QR's content: a compact JWS (live or static). Leave out when typing a number")
            @Size(max = 2000)
            String token,

            @Schema(example = "TECHFEST26-000042-7", description = "A typed pass number. Leave out when scanning a QR")
            @Size(max = 40)
            String passNumber,

            @Schema(description = "An event-service gate id the volunteer is assigned to") @NotNull
            Long gateId,

            @Schema(example = "phone-7f3a", description = "Which device scanned (scan log; offline merge in P7)")
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "letters, digits and . _ : - only")
            String deviceId,

            @Schema(description = "When the device scanned (its clock); default now")
            Instant scannedAt,

            @Schema(description = "IN (default) or OUT for check-out")
            ScanMode mode) {

        @AssertTrue(message = "send exactly one of token and passNumber")
        @Schema(hidden = true)
        public boolean isExactlyOneCode() {
            return (token == null || token.isBlank()) != (passNumber == null || passNumber.isBlank());
        }

        @AssertTrue(message = "mode is IN or OUT")
        @Schema(hidden = true)
        public boolean isInOrOut() {
            return mode != ScanMode.LOOKUP;
        }

        public ScanMode modeOrIn() {
            return mode == null ? ScanMode.IN : mode;
        }
    }

    /** The answer to an accepted scan (rejections are ProblemDetails with the same fields). */
    public record ScanResponse(
            UUID scanId,
            ScanResult result,
            String message,
            ScanResult.Color color,
            Attendee attendee,
            String eventCode,
            String gateCode,
            Long sessionId,
            String sessionTitle,
            Instant checkedInAt,
            boolean reentry,

            @Schema(description = "P7: check-ins at this gate so far (null when the live counters are unavailable)")
            Long gateCheckedIn) {

        public static ScanResponse from(ScanOutcome outcome, Long gateCheckedIn) {
            return new ScanResponse(
                    outcome.scanId(),
                    outcome.result(),
                    outcome.message(),
                    outcome.result().color(),
                    outcome.attendee(),
                    outcome.eventCode(),
                    outcome.gateCode(),
                    outcome.sessionId(),
                    outcome.sessionTitle(),
                    outcome.checkedInAt(),
                    outcome.reentry(),
                    gateCheckedIn);
        }
    }

    /** What the volunteer compares with the person before confirming a typed number. */
    public record LookupResponse(
            String passNumber,
            String holderName,
            String passStatus,
            PresenceState presence,
            String eventCode,
            String gateCode,
            Long sessionId,
            String sessionTitle) {

        public static LookupResponse from(Lookup lookup) {
            ScanOutcome outcome = lookup.outcome();
            return new LookupResponse(
                    outcome.attendee().passNumber(),
                    outcome.attendee().holderName(),
                    lookup.passStatus(),
                    lookup.presence(),
                    outcome.eventCode(),
                    outcome.gateCode(),
                    outcome.sessionId(),
                    outcome.sessionTitle());
        }
    }

    // ---- offline scanner (P7, docs/plans/phase-7.md §7-8) -------------------------------------------------------

    public record BatchRequest(
            @Schema(example = "phone-7f3a") @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+")
            String deviceId,

            @Schema(description = "Queued scans, any order (the server sorts by scannedAt)")
            @NotNull
            @Size(min = 1, max = 100)
            @Valid
            List<BatchItem> scans) {}

    public record BatchItem(
            @Schema(description = "The phone's own id for this queued scan; a resend gets the first answer")
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9._:-]+")
            String localId,

            @Size(max = 2000) String token,

            @Size(max = 40) String passNumber,

            @NotNull Long gateId,

            @Schema(description = "When the phone scanned (its clock); the pipeline runs as of this time") @NotNull
            Instant scannedAt) {

        @AssertTrue(message = "send exactly one of token and passNumber")
        @Schema(hidden = true)
        public boolean isExactlyOneCode() {
            return (token == null || token.isBlank()) != (passNumber == null || passNumber.isBlank());
        }
    }

    /** One answer per queued scan, in the order sent. Refusals are answers too (no problem+json here). */
    public record BatchResult(
            String localId,
            ScanResult result,
            String message,
            ScanResult.Color color,
            String passNumber,
            String holderName,
            Instant checkedInAt) {}

    public record BatchResponse(List<BatchResult> results) {}

    /** What a scanner keeps for offline checks: no token, no email; first names only (owner Q7). */
    public record ManifestResponse(
            long eventId,
            String eventCode,
            String issuer,
            long clockSkewSeconds,
            long doorsOpenBeforeSeconds,
            List<Window> sessions,
            List<ManifestPass> passes) {}

    /** {@code id} 0 = the whole event (an event without sessions). */
    public record Window(long id, Instant startsAt, Instant endsAt) {}

    public record ManifestPass(UUID passId, String passNumber, String firstName, String status) {}
}

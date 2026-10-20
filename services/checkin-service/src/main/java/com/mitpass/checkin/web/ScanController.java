package com.mitpass.checkin.web;

import com.mitpass.checkin.service.LiveStats;
import com.mitpass.checkin.service.ManifestService;
import com.mitpass.checkin.service.ScanOutcome;
import com.mitpass.checkin.service.ScanService;
import com.mitpass.checkin.service.ScanService.Lookup;
import com.mitpass.checkin.service.ScanService.OfflineScan;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import com.mitpass.checkin.web.dto.ScanDtos.BatchRequest;
import com.mitpass.checkin.web.dto.ScanDtos.BatchResponse;
import com.mitpass.checkin.web.dto.ScanDtos.BatchResult;
import com.mitpass.checkin.web.dto.ScanDtos.LookupResponse;
import com.mitpass.checkin.web.dto.ScanDtos.ManifestResponse;
import com.mitpass.checkin.web.dto.ScanDtos.ScanRequest;
import com.mitpass.checkin.web.dto.ScanDtos.ScanResponse;
import com.mitpass.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate scans (docs/plans/phase-5.md §7). VOLUNTEER or ORGANIZER (organizers are volunteers too, RL11). Who may scan at
 * which gate is the pipeline's first step: an assignment, or organizing the gate's event. So a 403 here can also be the
 * {@code scan/unauthorized-scanner} result.
 */
@RestController
@Validated
@RequestMapping("/api/scans")
@PreAuthorize("hasAnyRole('VOLUNTEER', 'ORGANIZER')")
@Tag(name = "Scans", description = "Verify what an attendee shows at a gate and check them in or out")
public class ScanController {

    private final ScanService scans;
    private final LiveStats live;
    private final ManifestService manifests;

    public ScanController(ScanService scans, LiveStats live, ManifestService manifests) {
        this.scans = scans;
        this.live = live;
        this.manifests = manifests;
    }

    @PostMapping
    @Operation(
            summary = "Scan a QR token or a typed pass number (assigned VOLUNTEER, or the event's ORGANIZER)",
            description = "200 ACCEPTED / CHECKED_OUT. Anything else is a problem+json with `result`, `message` and "
                    + "`color`: 409 for state conflicts (ALREADY_CHECKED_IN, REVOKED, NOT_YET_OPEN, …), 422 for an "
                    + "unusable code, 404 UNKNOWN_PASS, 403 UNAUTHORIZED_SCANNER.")
    public ResponseEntity<ScanResponse> scan(@Valid @RequestBody ScanRequest request, CurrentUser volunteer) {
        ScanOutcome outcome = scans.scan(
                volunteer,
                new ScanCommand(
                        blankToNull(request.token()),
                        blankToNull(request.passNumber()),
                        request.gateId(),
                        request.deviceId(),
                        request.scannedAt(),
                        request.modeOrIn()));
        if (!outcome.accepted()) {
            throw new ScanRejectedException(outcome);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ScanResponse.from(outcome, live.gateCheckedIn(outcome.eventId(), outcome.gateCode())));
    }

    @GetMapping("/lookup")
    @Operation(
            summary = "Look a typed pass number up for a visual check (VOLUNTEER)",
            description = "The holder's name and the pass's state, without checking in. Confirm with POST /api/scans "
                    + "and the same number. Refusals are the same problem+json results as a scan.")
    public ResponseEntity<LookupResponse> lookup(
            @RequestParam @Size(max = 40) String passNumber,
            @RequestParam long gateId,
            @RequestParam(defaultValue = "unknown") @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+")
                    String deviceId,
            CurrentUser volunteer) {
        Lookup lookup = scans.lookup(volunteer, passNumber, gateId, deviceId);
        if (!lookup.outcome().accepted()) {
            throw new ScanRejectedException(lookup.outcome());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LookupResponse.from(lookup));
    }

    @PostMapping("/batch")
    @Operation(
            summary = "Sync scans queued while offline (assigned VOLUNTEER, or the event's ORGANIZER)",
            description = "Each scan runs through the full pipeline as of its scannedAt, oldest first. The check-in "
                    + "already on record wins: a later one is DUPLICATE_OFFLINE. Older than 12 h or in the future: "
                    + "REJECTED_OFFLINE_CLOCK. A resent localId gets its first answer. Always 200, one answer each.")
    public ResponseEntity<BatchResponse> batch(@Valid @RequestBody BatchRequest request, CurrentUser volunteer) {
        List<OfflineScan> scans = request.scans().stream()
                .map(s -> new OfflineScan(
                        s.localId(), blankToNull(s.token()), blankToNull(s.passNumber()), s.gateId(), s.scannedAt()))
                .toList();
        List<ScanOutcome> outcomes = this.scans.batch(volunteer, request.deviceId(), scans);
        List<BatchResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < scans.size(); i++) {
            ScanOutcome o = outcomes.get(i);
            results.add(new BatchResult(
                    scans.get(i).localId(),
                    o.result(),
                    o.message(),
                    o.result().color(),
                    o.attendee() == null ? null : o.attendee().passNumber(),
                    o.attendee() == null ? null : o.attendee().holderName(),
                    o.checkedInAt()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new BatchResponse(results));
    }

    @GetMapping("/manifest")
    @PreAuthorize("hasAnyRole('VOLUNTEER', 'ORGANIZER') and @checkinSecurity.mayScan(#eventId)")
    @Operation(
            summary = "What a scanner keeps for offline checks (assigned VOLUNTEER, or the event's ORGANIZER)",
            description = "Sessions, the pipeline's time rules, and every pass with its status (first names only).")
    public ResponseEntity<ManifestResponse> manifest(@RequestParam Long eventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(manifests.manifest(eventId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

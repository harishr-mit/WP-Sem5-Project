package com.mitpass.checkin.web;

import com.mitpass.checkin.service.ScanOutcome;
import com.mitpass.checkin.service.ScanService;
import com.mitpass.checkin.service.ScanService.Lookup;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import com.mitpass.checkin.web.dto.ScanDtos.LookupResponse;
import com.mitpass.checkin.web.dto.ScanDtos.ScanRequest;
import com.mitpass.checkin.web.dto.ScanDtos.ScanResponse;
import com.mitpass.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * Gate scans (docs/plans/phase-5.md §7). VOLUNTEER only; the assignment to the gate's event and gate is checked by the
 * pipeline's first step, so a 403 here can also be the {@code scan/unauthorized-scanner} result.
 */
@RestController
@Validated
@RequestMapping("/api/scans")
@PreAuthorize("hasRole('VOLUNTEER')")
@Tag(name = "Scans", description = "Verify what an attendee shows at a gate and check them in or out")
public class ScanController {

    private final ScanService scans;

    public ScanController(ScanService scans) {
        this.scans = scans;
    }

    @PostMapping
    @Operation(
            summary = "Scan a QR token or a typed pass number (VOLUNTEER assigned to the gate)",
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
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ScanResponse.from(outcome));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

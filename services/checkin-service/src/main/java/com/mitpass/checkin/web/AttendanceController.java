package com.mitpass.checkin.web;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.checkin.service.AttendanceReportService;
import com.mitpass.checkin.service.CheckInVoids;
import com.mitpass.checkin.service.CheckinProblems;
import com.mitpass.checkin.service.LiveStats;
import com.mitpass.checkin.service.PassCopyReconciler;
import com.mitpass.checkin.service.PassCopyReconciler.SyncReport;
import com.mitpass.checkin.web.dto.AttendanceDtos.AttendanceSummary;
import com.mitpass.checkin.web.dto.AttendanceDtos.AttendeeRow;
import com.mitpass.checkin.web.dto.AttendanceDtos.CertificateRow;
import com.mitpass.checkin.web.dto.AttendanceDtos.MyAttendance;
import com.mitpass.checkin.web.dto.AttendanceDtos.SyncResponse;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.web.paging.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Attendance reports (docs/plans/phase-5.md §7): the event's organizer or ADMIN; a student's own attendance. */
@RestController
@Validated
@RequestMapping("/api/attendance")
@Tag(name = "Attendance", description = "Who came: per event, session and gate; CSV export; certificates")
public class AttendanceController {

    private static final String OWNER_OR_ADMIN = "@checkinSecurity.ownsEvent(#eventId)";

    private final AttendanceReportService reports;
    private final PassCopyReconciler reconciler;
    private final LiveStats live;
    private final EventCatalog catalog;
    private final CheckInVoids voids;

    public AttendanceController(
            AttendanceReportService reports,
            PassCopyReconciler reconciler,
            LiveStats live,
            EventCatalog catalog,
            CheckInVoids voids) {
        this.reports = reports;
        this.reconciler = reconciler;
        this.live = live;
        this.catalog = catalog;
        this.voids = voids;
    }

    @GetMapping("/events/{eventId}/live")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(
            summary = "The live dashboard's snapshot (event organizer or ADMIN)",
            description =
                    "The same snapshot the STOMP feed pushes to /topic/events/{id} (P7): first paint and fallback.")
    public LiveStats.LiveSnapshot live(@PathVariable Long eventId) {
        return live.snapshot(eventId, catalog.event(eventId).capacity());
    }

    /** RL12: a wrong check-in is voided by the event's organizer or an admin, with a reason (RL9: the portal asks). */
    @PostMapping("/events/{eventId}/void")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(
            summary = "Void wrong check-ins of a pass (event organizer or ADMIN; RL12)",
            description = "Without `sessionId`, every check-in of the pass at this event. The rows are kept for audit, "
                    + "left out of every count and report, and the pass can be checked in again. 404 if there is "
                    + "nothing to void. Publishes AttendanceVoided.")
    public CheckInVoids.Voided voidCheckIns(
            @PathVariable Long eventId, @Valid @RequestBody VoidRequest request, CurrentUser caller) {
        return voids.voidCheckIns(
                eventId,
                request.passId(),
                request.sessionId(),
                request.reason().strip(),
                caller.id().toString());
    }

    /** {@code reason}: why, for the audit trail (required). */
    public record VoidRequest(
            @NotNull UUID passId,
            Long sessionId,
            @NotBlank @Size(max = 200) String reason) {}

    @GetMapping("/events/{eventId}/summary")
    // SERVICE: event-service asks before an undo of LIVE whether anyone has checked in (RL3, P8)
    @PreAuthorize("hasRole('SERVICE') or " + OWNER_OR_ADMIN)
    @Operation(summary = "Counts and percentages per event, session and gate (event organizer or ADMIN)")
    public AttendanceSummary summary(@PathVariable Long eventId) {
        return reports.summary(eventId);
    }

    @GetMapping("/events/{eventId}/attendees")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(summary = "One row per pass, by pass number; filter by presence (event organizer or ADMIN)")
    public PageResponse<AttendeeRow> attendees(
            @PathVariable Long eventId,
            @RequestParam(required = false) @Pattern(regexp = "IN|OUT|NOT_ARRIVED") String presence,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return reports.attendees(eventId, presence, page, size);
    }

    @GetMapping("/events/{eventId}/export.csv")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(summary = "Every pass as CSV (event organizer or ADMIN)")
    public void exportCsv(@PathVariable Long eventId, HttpServletResponse response) throws IOException {
        String code = reports.summary(eventId).eventCode(); // 404 before anything is written
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(code + "-attendance.csv")
                        .build()
                        .toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        reports.exportCsv(eventId, out);
        out.flush();
    }

    @GetMapping("/events/{eventId}/certificates")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(summary = "Who qualifies for a certificate: attended at least the required sessions (organizer, ADMIN)")
    public List<CertificateRow> certificates(@PathVariable Long eventId) {
        return reports.certificates(eventId);
    }

    @PostMapping("/events/{eventId}/sync")
    @PreAuthorize(OWNER_OR_ADMIN)
    @Operation(
            summary = "Re-read this event's passes from pass-service now (admin tool; event organizer or ADMIN)",
            description = "Not needed in normal use: the reconciler does this by itself every few minutes and when "
                    + "the event goes LIVE. 503 if pass-service can't be reached.")
    public SyncResponse sync(@PathVariable Long eventId) {
        try {
            SyncReport report = reconciler.syncEvent(eventId);
            return new SyncResponse(report.eventId(), report.passes(), report.revoked());
        } catch (RuntimeException ex) {
            throw CheckinProblems.passServiceUnavailable();
        }
    }

    @GetMapping("/me")
    @Operation(summary = "My attendance: sessions attended and certificate eligibility per event")
    public List<MyAttendance> mine(CurrentUser me) {
        return reports.mine(me.id());
    }
}

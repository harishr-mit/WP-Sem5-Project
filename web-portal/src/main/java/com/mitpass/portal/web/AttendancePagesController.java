package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels;
import com.mitpass.portal.client.ApiModels.AttendanceSummary;
import com.mitpass.portal.client.ApiModels.AttendeeRow;
import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.SyncResult;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import com.mitpass.portal.web.support.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The organizer's Live and Reports tabs (UI reference screen 7): the live dashboard (P7), attendance figures with a
 * Refresh button, the
 * CSV passed through byte for byte, and the admin-only "sync passes now" (normally the reconciler does it by itself).
 */
@Controller
public class AttendancePagesController {

    private final GatewayClient gateway;
    private final MessageSource messages;
    private final PortalProperties properties;

    public AttendancePagesController(GatewayClient gateway, MessageSource messages, PortalProperties properties) {
        this.gateway = gateway;
        this.messages = messages;
        this.properties = properties;
    }

    /** Screen 7. The Refresh button (HTMX) gets only the board; the page itself also works on a plain reload. */
    @GetMapping("/organizer/events/{id}/attendance")
    public String attendance(
            @PathVariable long id, @AuthenticationPrincipal PortalUser user, HttpServletRequest request, Model model) {
        AttendanceSummary summary = gateway.attendanceSummary(id);
        model.addAttribute("summary", summary);
        model.addAttribute(
                "gateMax",
                summary.gates().stream().mapToLong(g -> g.checkedIn()).max().orElse(0));
        model.addAttribute("attendees", gateway.attendees(id));
        model.addAttribute("certificates", gateway.certificates(id));
        model.addAttribute("event", gateway.event(id));
        if (Htmx.isPartial(request)) {
            return "organizer/attendance :: board";
        }
        // "Sync passes now" is an admin tool only: the reconciler keeps the copy in step by itself (D14).
        model.addAttribute("isAdmin", user != null && user.hasRole("ADMIN"));
        return "organizer/attendance";
    }

    /** RL12 + RL9: a void leads to a confirmation page that asks for the reason; only a confirmed POST acts. */
    @GetMapping("/organizer/events/{id}/attendance/{passId}/void")
    public String confirmVoid(@PathVariable long id, @PathVariable UUID passId, Model model) {
        AttendeeRow row = gateway.attendees(id).content().stream()
                .filter(a -> passId.equals(a.passId()))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
        model.addAttribute("attendee", row);
        model.addAttribute("eventId", id);
        return "organizer/confirm-void";
    }

    @PostMapping("/organizer/events/{id}/attendance/{passId}/void")
    public String voidCheckIn(
            @PathVariable long id,
            @PathVariable UUID passId,
            @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "false") boolean confirmed,
            RedirectAttributes redirect) {
        if (!confirmed || reason.isBlank()) {
            return "redirect:/organizer/events/" + id + "/attendance/" + passId + "/void";
        }
        try {
            gateway.voidCheckIn(id, passId, reason.strip());
            redirect.addFlashAttribute(
                    "message", messages.getMessage("void.done", null, LocaleContextHolder.getLocale()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events/" + id + "/attendance";
    }

    /**
     * The live dashboard (screen 7, P7): first paint from the snapshot; {@code dashboard.js} then follows
     * {@code /topic/events/{id}} on {@code /portal-ws} (LiveRelay). Without JavaScript it is a page that shows the
     * numbers as of loading.
     */
    @GetMapping("/organizer/events/{id}/live")
    public String live(@PathVariable long id, Model model) {
        model.addAttribute("event", gateway.event(id));
        model.addAttribute("live", gateway.liveSnapshot(id));
        model.addAttribute(
                "fallbackPollSeconds", properties.live().fallbackPoll().toSeconds());
        return "organizer/live";
    }

    /** The same snapshot as JSON, for the page's chart and its fallback polling while the socket is down. */
    @GetMapping(value = "/organizer/events/{id}/live.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<ApiModels.LiveSnapshot> liveJson(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gateway.liveSnapshot(id));
    }

    @GetMapping("/organizer/events/{id}/attendance.csv")
    public ResponseEntity<byte[]> csv(@PathVariable long id) {
        Binary csv = gateway.attendanceCsv(id);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .cacheControl(CacheControl.noStore());
        if (csv.contentDisposition() != null) {
            ok.header(HttpHeaders.CONTENT_DISPOSITION, csv.contentDisposition());
        }
        return ok.body(csv.body());
    }

    @PostMapping("/organizer/events/{id}/attendance/sync")
    public String sync(@PathVariable long id, RedirectAttributes redirect) {
        try {
            SyncResult result = gateway.syncAttendance(id);
            redirect.addFlashAttribute(
                    "message",
                    messages.getMessage(
                            "att.synced",
                            new Object[] {result.passes(), result.revoked()},
                            LocaleContextHolder.getLocale()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events/" + id + "/attendance";
    }
}

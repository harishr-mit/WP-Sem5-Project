package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.SyncResult;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The organizer's attendance page (UI reference screen 7, thin; D9: counts update on reload, live in P7) with the CSV
 * passed through byte for byte, and the admin "sync passes now" action (normally the reconciler does it by itself).
 */
@Controller
public class AttendancePagesController {

    private final GatewayClient gateway;

    public AttendancePagesController(GatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/organizer/events/{id}/attendance")
    public String attendance(@PathVariable long id, Model model) {
        model.addAttribute("summary", gateway.attendanceSummary(id));
        model.addAttribute("attendees", gateway.attendees(id));
        model.addAttribute("certificates", gateway.certificates(id));
        return "organizer/attendance";
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
                    "Synced %d pass(es) from pass-service (%d revoked).".formatted(result.passes(), result.revoked()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events/" + id + "/attendance";
    }
}

package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Assignment;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.ApiModels.LookupAnswer;
import com.mitpass.portal.client.ApiModels.ScanAnswer;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The volunteer's scan page (UI reference screens 5–6; D9 slice: a text field, no camera). One field takes either a
 * typed pass number, which goes through lookup and a visual check first, or a pasted QR token, which is scanned
 * straight away. Every POST redirects (PRG); the result travels as a flash attribute. Gate and mode are remembered in
 * the session, and so is a device id for the scan log.
 */
@Controller
public class ScanPagesController {

    /** Looks like a pass number ({@code TECHFEST26-000042-7}); anything else is treated as a token. */
    static final Pattern PASS_NUMBER = Pattern.compile("^[A-Za-z0-9]{3,16}-\\d{6,9}-\\d$");

    static final String GATE = "scan.gateId";
    static final String MODE = "scan.mode";
    static final String DEVICE = "scan.deviceId";

    /** One choice in the gate selector. */
    public record GateOption(long gateId, String label) {}

    private final GatewayClient gateway;

    public ScanPagesController(GatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/scan")
    public String scanPage(@AuthenticationPrincipal PortalUser user, Model model, HttpSession session) {
        List<GateOption> options = gateOptions(user);
        Long remembered = (Long) session.getAttribute(GATE);
        boolean stillAssigned = remembered != null && options.stream().anyMatch(o -> o.gateId() == remembered);
        Long gateId = stillAssigned
                ? remembered
                : options.isEmpty() ? null : options.getFirst().gateId();
        model.addAttribute("options", options);
        model.addAttribute("gateId", gateId);
        model.addAttribute("mode", session.getAttribute(MODE) == null ? "IN" : session.getAttribute(MODE));
        return "scan/scan";
    }

    @PostMapping("/scan")
    public String scan(
            @RequestParam long gateId,
            @RequestParam(defaultValue = "IN") String mode,
            @RequestParam(defaultValue = "") String code,
            HttpSession session,
            RedirectAttributes redirect) {
        String inOut = "OUT".equals(mode) ? "OUT" : "IN";
        session.setAttribute(GATE, gateId);
        session.setAttribute(MODE, inOut);
        String input = code.strip();
        if (input.isEmpty()) {
            redirect.addFlashAttribute("error", "Type a pass number or paste the code from the QR.");
            return "redirect:/scan";
        }
        try {
            if (PASS_NUMBER.matcher(input).matches()) {
                LookupAnswer answer = gateway.lookup(input, gateId, device(session));
                if (answer.refusal() != null) {
                    redirect.addFlashAttribute("result", answer.refusal());
                    return "redirect:/scan";
                }
                redirect.addFlashAttribute("lookup", answer.pass());
                return "redirect:/scan/confirm";
            }
            redirect.addFlashAttribute("result", gateway.scan(input, null, gateId, device(session), inOut));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/scan";
    }

    /** Manual entry, step two: compare the name with the person, then confirm. */
    @GetMapping("/scan/confirm")
    public String confirmPage(Model model, HttpSession session) {
        if (!model.containsAttribute("lookup")) {
            return "redirect:/scan";
        }
        model.addAttribute("gateId", session.getAttribute(GATE));
        model.addAttribute("mode", session.getAttribute(MODE) == null ? "IN" : session.getAttribute(MODE));
        return "scan/confirm";
    }

    @PostMapping("/scan/confirm")
    public String confirm(
            @RequestParam String passNumber,
            @RequestParam long gateId,
            @RequestParam(defaultValue = "IN") String mode,
            HttpSession session,
            RedirectAttributes redirect) {
        try {
            ScanAnswer result =
                    gateway.scan(null, passNumber.strip(), gateId, device(session), "OUT".equals(mode) ? "OUT" : "IN");
            redirect.addFlashAttribute("result", result);
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/scan";
    }

    /**
     * Every gate the user may scan at: assigned gates (all gates of an "any gate" assignment), and for an organizer every
     * gate of the events they organize (RL11). A gate appears once.
     */
    private List<GateOption> gateOptions(PortalUser user) {
        Map<Long, GateOption> options = new LinkedHashMap<>();
        if (user.hasRole("ORGANIZER")) {
            for (EventSummary event : gateway.events(null, null, user.id(), 0).content()) {
                for (Gate gate : gateway.gates(event.id())) {
                    options.putIfAbsent(
                            gate.id(), new GateOption(gate.id(), event.code() + " · " + gate.name() + " (your event)"));
                }
            }
        }
        if (user.hasRole("VOLUNTEER")) {
            for (Assignment a : gateway.myAssignments()) {
                List<Gate> gates = a.gate() != null ? List.of(a.gate()) : gateway.gates(a.eventId());
                for (Gate gate : gates) {
                    options.putIfAbsent(gate.id(), new GateOption(gate.id(), a.eventCode() + " · " + gate.name()));
                }
            }
        }
        return new ArrayList<>(options.values());
    }

    /** A random id per browser session: the scan log tells this device's scans apart. */
    private static String device(HttpSession session) {
        String id = (String) session.getAttribute(DEVICE);
        if (id == null) {
            byte[] random = new byte[4];
            ThreadLocalRandom.current().nextBytes(random);
            id = "portal-" + HexFormat.of().formatHex(random);
            session.setAttribute(DEVICE, id);
        }
        return id;
    }
}

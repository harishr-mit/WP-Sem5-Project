package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Assignment;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.ApiModels.LookupAnswer;
import com.mitpass.portal.client.ApiModels.ScanAnswer;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scanner (UI reference screens 5–6; docs/plans/phase-6.md §8). With JavaScript the phone's camera reads the QR
 * (html5-qrcode) and posts the text to {@code /scan/submit}, which answers the result card and an {@code HX-Trigger}
 * ({@code scanResult}) that makes {@code scanner.js} flash the screen, play a tone and vibrate. Typed pass numbers go
 * through {@code /scan/check} (the holder's name for a visual check) and then {@code /scan/submit}.
 *
 * <p>Without JavaScript (or without a camera) the same page works as in P5: one field takes a typed pass number (lookup
 * and visual check first) or a pasted token; every POST redirects (PRG) and the result travels as a flash attribute.
 * Gate and mode are remembered in the session, and so is a device id for the scan log; the camera page sends its own
 * device id, kept in the browser.
 */
@Controller
public class ScanPagesController {

    /** Looks like a pass number ({@code TECHFEST26-000042-7}); anything else is treated as a token. */
    static final Pattern PASS_NUMBER = Pattern.compile("^[A-Za-z0-9]{3,16}-\\d{6,9}-\\d$");

    /** A device id sent by the browser: letters, digits and dashes only (it goes into the scan log). */
    static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9-]{4,40}$");

    static final String GATE = "scan.gateId";
    static final String MODE = "scan.mode";
    static final String DEVICE = "scan.deviceId";

    /** One choice in the gate selector; the event id tells the offline scanner which bundle it needs (P7). */
    public record GateOption(long gateId, String label, long eventId) {}

    private final GatewayClient gateway;
    private final PortalProperties properties;
    private final JsonMapper json;
    private final MessageSource messages;

    public ScanPagesController(
            GatewayClient gateway, PortalProperties properties, JsonMapper json, MessageSource messages) {
        this.gateway = gateway;
        this.properties = properties;
        this.json = json;
        this.messages = messages;
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
        model.addAttribute("repeatIgnoreMillis", properties.scanRepeatIgnore().toMillis());
        model.addAttribute("offline", properties.offline());
        return "scan/scan";
    }

    // ---- offline scanner (P7, docs/plans/phase-7.md §7) -----------------------------------------------------------

    /**
     * What the phone keeps to check passes while offline: the public pass keys and the event's manifest. No token of
     * any kind is in it (NoTokensInPagesTest).
     */
    @GetMapping(value = "/scan/offline/bundle", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> offlineBundle(@RequestParam long eventId) {
        String body = "{\"eventId\":" + eventId
                + ",\"jwks\":" + gateway.passJwks()
                + ",\"manifest\":" + gateway.scanManifest(eventId) + "}";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** The phone's queued scans, passed to checkin-service as they are; one answer per scan comes back. */
    @PostMapping(
            value = "/scan/batch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> offlineBatch(@RequestBody String body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gateway.scanBatch(body));
    }

    /**
     * A fresh CSRF token for the sync: the page may have been loaded (or come from the offline cache) long ago. A
     * logged-out session gets the login redirect instead, which the page reads as "log in to sync".
     */
    @GetMapping(value = "/scan/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("header", token.getHeaderName(), "token", token.getToken()));
    }

    /**
     * HTMX: a camera read (token) or a confirmed pass number. Answers the result card; the {@code HX-Trigger} header
     * carries the colour for the flash, tone and vibration. A refusal is a result like any other (200).
     */
    @PostMapping("/scan/submit")
    public String submit(
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String passNumber,
            @RequestParam long gateId,
            @RequestParam(defaultValue = "IN") String mode,
            @RequestParam(required = false) String deviceId,
            HttpSession session,
            HttpServletResponse response,
            Model model) {
        String inOut = "OUT".equals(mode) ? "OUT" : "IN";
        session.setAttribute(GATE, gateId);
        session.setAttribute(MODE, inOut);
        String device = deviceId != null && DEVICE_ID.matcher(deviceId).matches() ? deviceId : device(session);
        try {
            ScanAnswer answer = token != null && !token.isBlank()
                    ? gateway.scan(token.strip(), null, gateId, device, inOut)
                    : gateway.scan(null, passNumber == null ? null : passNumber.strip(), gateId, device, inOut);
            model.addAttribute("result", answer);
            trigger(response, answer.colorClass(), answer.result());
        } catch (ApiProblemException ex) {
            model.addAttribute("problem", ex.getMessage());
            trigger(response, "red", "ERROR");
        }
        return "scan/scan :: result";
    }

    /**
     * HTMX, the manual tab: a typed pass number gets the holder's name for a visual check (or the refusal as a result
     * card); a pasted QR token is scanned straight away, as on the no-JavaScript path.
     */
    @PostMapping("/scan/check")
    public String check(
            @RequestParam(defaultValue = "") String code,
            @RequestParam long gateId,
            @RequestParam(defaultValue = "IN") String mode,
            @RequestParam(required = false) String deviceId,
            HttpSession session,
            HttpServletResponse response,
            Model model) {
        String input = code.strip();
        if (!PASS_NUMBER.matcher(input).matches()) {
            return submit(input, null, gateId, mode, deviceId, session, response, model);
        }
        String device = deviceId != null && DEVICE_ID.matcher(deviceId).matches() ? deviceId : device(session);
        try {
            LookupAnswer answer = gateway.lookup(input, gateId, device);
            if (answer.refusal() != null) {
                model.addAttribute("result", answer.refusal());
                trigger(
                        response,
                        answer.refusal().colorClass(),
                        answer.refusal().result());
                return "scan/scan :: result";
            }
            model.addAttribute("lookup", answer.pass());
            model.addAttribute("gateId", gateId);
            model.addAttribute("mode", "OUT".equals(mode) ? "OUT" : "IN");
            return "scan/scan :: lookup";
        } catch (ApiProblemException ex) {
            model.addAttribute("problem", ex.getMessage());
            trigger(response, "red", "ERROR");
            return "scan/scan :: result";
        }
    }

    /** {@code HX-Trigger: {"scanResult": {"color": "green", "result": "ACCEPTED"}}} for scanner.js. */
    private void trigger(HttpServletResponse response, String color, String result) {
        Map<String, Object> detail = Map.of("color", color, "result", result == null ? "" : result);
        response.setHeader("HX-Trigger", json.writeValueAsString(Map.of("scanResult", detail)));
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
                            gate.id(),
                            new GateOption(
                                    gate.id(), event.code() + " · " + gate.name() + " " + yourEvent(), event.id()));
                }
            }
        }
        if (user.hasRole("VOLUNTEER")) {
            for (Assignment a : gateway.myAssignments()) {
                List<Gate> gates = a.gate() != null ? List.of(a.gate()) : gateway.gates(a.eventId());
                for (Gate gate : gates) {
                    options.putIfAbsent(
                            gate.id(), new GateOption(gate.id(), a.eventCode() + " · " + gate.name(), a.eventId()));
                }
            }
        }
        return new ArrayList<>(options.values());
    }

    private String yourEvent() {
        return messages.getMessage("scan.yourEvent", null, "(your event)", LocaleContextHolder.getLocale());
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

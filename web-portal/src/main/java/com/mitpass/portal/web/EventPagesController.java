package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Eligibility;
import com.mitpass.portal.client.ApiModels.RegisterResult;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Catalogue, event detail and registering (UI reference screen 2; P3 slice, D9). */
@Controller
public class EventPagesController {

    private final GatewayClient gateway;

    public EventPagesController(GatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/events")
    public String catalogue(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        model.addAttribute("events", gateway.events(q, category, null, Math.max(0, page)));
        model.addAttribute("categories", gateway.categories());
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        return "events/list";
    }

    @GetMapping("/events/{id}")
    public String detail(@PathVariable long id, @AuthenticationPrincipal PortalUser user, Model model) {
        model.addAttribute("event", gateway.event(id));
        if (user != null) {
            Eligibility eligibility = gateway.eligibility(id);
            model.addAttribute("eligibility", eligibility);
        }
        // A fresh key per page view: a double click (or a browser retry) replays instead of failing (D10).
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        return "events/detail";
    }

    @PostMapping("/events/{id}/register")
    public String register(@PathVariable long id, @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        try {
            RegisterResult result = gateway.register(id, idempotencyKey);
            Registration registration = result.registration();
            if ("CONFIRMED".equals(registration.status())) {
                redirect.addFlashAttribute(
                        "message",
                        "You're in: your registration is CONFIRMED. Your pass is being issued; it appears in My passes in a moment.");
            } else {
                redirect.addFlashAttribute(
                        "message",
                        "The event is full, so you're on the waitlist at position %d. If a seat frees up, you move up automatically."
                                .formatted(registration.waitlistPosition()));
            }
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", describe(ex));
        }
        return "redirect:/events/" + id;
    }

    static String describe(ApiProblemException ex) {
        if (ex.getStatus() == 403) {
            return "Only students can register for events.";
        }
        if (!ex.getReasons().isEmpty()) {
            return "You can't register: " + String.join("; ", ex.getReasons());
        }
        return ex.getMessage();
    }
}

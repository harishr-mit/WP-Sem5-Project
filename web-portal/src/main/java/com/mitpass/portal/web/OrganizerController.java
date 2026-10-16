package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The organizer's "My events" with one button per allowed lifecycle move. The buttons come straight from the event's
 * {@code allowedTransitions}, so the page can't offer an illegal move (and never LIVE: only the scheduler starts an
 * event, D10).
 */
@Controller
public class OrganizerController {

    private final GatewayClient gateway;
    private final LifecycleActions actions;

    public OrganizerController(GatewayClient gateway, LifecycleActions actions) {
        this.gateway = gateway;
        this.actions = actions;
    }

    @GetMapping("/organizer/events")
    public String myEvents(@AuthenticationPrincipal PortalUser user, Model model) {
        List<EventSummary> mine = gateway.events(null, null, user.id(), 0).content();
        // One detail call per event for its allowed moves: fine for a slice (a page holds at most 20 events).
        List<EventDetail> details =
                mine.stream().map(e -> gateway.event(e.id())).toList();
        model.addAttribute("events", details);
        return "organizer/events";
    }

    /**
     * The event's registrations with each one's pass and a revoke button (docs/plans/phase-4.md §10). Registrations
     * come from event-service and passes from pass-service, joined here by registration id.
     */
    @GetMapping("/organizer/events/{id}/registrations")
    public String registrations(@PathVariable long id, Model model) {
        EventDetail event = gateway.event(id);
        Map<UUID, Pass> passes = gateway.eventPasses(id).content().stream()
                .collect(Collectors.toMap(Pass::registrationId, pass -> pass, (a, b) -> a));
        List<Row> rows = gateway.eventRegistrations(id).content().stream()
                .map(registration -> new Row(registration, passes.get(registration.id())))
                .toList();
        model.addAttribute("event", event);
        model.addAttribute("rows", rows);
        return "organizer/registrations";
    }

    /** RL9: revoking can't be undone, so the first click only asks (PRG to the confirmation page). */
    @PostMapping("/organizer/passes/{passId}/revoke")
    public String revoke(
            @PathVariable UUID passId,
            @RequestParam long eventId,
            @RequestParam(required = false) String note,
            @RequestParam(defaultValue = "false") boolean confirmed,
            RedirectAttributes redirect) {
        if (!confirmed) {
            redirect.addAttribute("eventId", eventId);
            if (note != null && !note.isBlank()) {
                redirect.addAttribute("note", note.strip());
            }
            return "redirect:/organizer/passes/" + passId + "/revoke/confirm";
        }
        try {
            Pass pass = gateway.revokePass(passId, note);
            redirect.addFlashAttribute("message", "Pass %s is now %s.".formatted(pass.passNumber(), pass.status()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events/" + eventId + "/registrations";
    }

    @GetMapping("/organizer/passes/{passId}/revoke/confirm")
    public String confirmRevoke(
            @PathVariable UUID passId,
            @RequestParam long eventId,
            @RequestParam(required = false) String note,
            Model model) {
        model.addAttribute("pass", gateway.pass(passId));
        model.addAttribute("eventId", eventId);
        model.addAttribute("note", note);
        return "organizer/confirm-revoke";
    }

    /** One line of the registrations table; {@code pass} is null until pass-service has issued it (or never). */
    public record Row(Registration registration, Pass pass) {}

    /**
     * A lifecycle move. Critical ones (cancel, end, undo start: RL9) first go to a confirmation page; cancelling also
     * needs the event code typed in. The check is here on the server, so a replayed or scripted form can't skip it.
     */
    @PostMapping("/organizer/events/{id}/transitions")
    public String transition(
            @PathVariable long id,
            @RequestParam String to,
            @RequestParam(defaultValue = "false") boolean confirmed,
            @RequestParam(required = false) String code,
            RedirectAttributes redirect) {
        EventDetail current = gateway.event(id);
        if (actions.isCritical(current.status(), to)) {
            String confirmPage = "redirect:/organizer/events/" + id + "/transitions/confirm?to=" + to;
            if (!confirmed) {
                return confirmPage;
            }
            if (actions.needsCode(to) && (code == null || !code.strip().equalsIgnoreCase(current.code()))) {
                redirect.addFlashAttribute(
                        "error", "Type the event code %s exactly to cancel the event.".formatted(current.code()));
                return confirmPage;
            }
        }
        try {
            EventDetail event = gateway.transition(id, to);
            redirect.addFlashAttribute("message", "%s is now %s.".formatted(event.title(), event.status()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events";
    }

    @GetMapping("/organizer/events/{id}/transitions/confirm")
    public String confirmTransition(@PathVariable long id, @RequestParam String to, Model model) {
        EventDetail event = gateway.event(id);
        boolean allowed =
                event.allowedTransitions() != null && event.allowedTransitions().contains(to);
        if (!allowed || !actions.isCritical(event.status(), to)) {
            return "redirect:/organizer/events";
        }
        model.addAttribute("event", event);
        model.addAttribute("to", to);
        model.addAttribute("label", actions.label(event.status(), to));
        model.addAttribute("consequence", actions.consequence(event.status(), to));
        model.addAttribute("needsCode", actions.needsCode(to));
        return "organizer/confirm-transition";
    }
}

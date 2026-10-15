package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.util.List;
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

    public OrganizerController(GatewayClient gateway) {
        this.gateway = gateway;
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

    @PostMapping("/organizer/events/{id}/transitions")
    public String transition(@PathVariable long id, @RequestParam String to, RedirectAttributes redirect) {
        try {
            EventDetail event = gateway.transition(id, to);
            redirect.addFlashAttribute("message", "%s is now %s.".formatted(event.title(), event.status()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events";
    }
}

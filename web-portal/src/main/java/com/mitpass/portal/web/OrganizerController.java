package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.web.support.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * An organizer's actions on one event: lifecycle moves (only those in the event's {@code allowedTransitions}, so never
 * LIVE: only the scheduler starts an event, D10; critical ones confirmed first, RL9), and the registrations with their
 * passes and the revoke action. The list and the event form are in {@link OrganizerEventsController}.
 */
@Controller
public class OrganizerController {

    private final GatewayClient gateway;
    private final LifecycleActions actions;
    private final DisplayFormat fmt;
    private final MessageSource messages;

    public OrganizerController(
            GatewayClient gateway, LifecycleActions actions, DisplayFormat fmt, MessageSource messages) {
        this.gateway = gateway;
        this.actions = actions;
        this.fmt = fmt;
        this.messages = messages;
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /**
     * The event's registrations with each one's pass and a revoke action (docs/plans/phase-4.md §10, restyled in P6).
     * Registrations come from event-service and passes from pass-service, joined here by registration id. {@code q}
     * filters by name, email or pass number; HTMX gets only the rows.
     */
    @GetMapping("/organizer/events/{id}/registrations")
    public String registrations(
            @PathVariable long id, @RequestParam(required = false) String q, HttpServletRequest request, Model model) {
        Map<UUID, Pass> passes = gateway.eventPasses(id).content().stream()
                .collect(Collectors.toMap(Pass::registrationId, pass -> pass, (a, b) -> a));
        List<Row> all = gateway.eventRegistrations(id).content().stream()
                .map(registration -> new Row(registration, passes.get(registration.id())))
                .toList();
        String needle = q == null ? "" : q.strip().toLowerCase(java.util.Locale.ROOT);
        List<Row> rows = needle.isEmpty()
                ? all
                : all.stream().filter(r -> r.matches(needle)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        if (Htmx.isPartial(request)) {
            return "organizer/registrations :: rows";
        }
        model.addAttribute("event", gateway.event(id));
        model.addAttribute("counts", Counts.of(all));
        return "organizer/registrations";
    }

    /** The four tiles above the table (UI reference screen 7, "Registrations" panel). */
    public record Counts(long confirmed, long waitlisted, long cancelled, long revoked) {
        static Counts of(List<Row> rows) {
            return new Counts(
                    rows.stream()
                            .filter(r -> "CONFIRMED".equals(r.registration().status()))
                            .count(),
                    rows.stream()
                            .filter(r -> "WAITLISTED".equals(r.registration().status()))
                            .count(),
                    rows.stream()
                            .filter(r -> "CANCELLED".equals(r.registration().status()))
                            .count(),
                    rows.stream()
                            .filter(r -> r.pass() != null
                                    && "REVOKED".equals(r.pass().status()))
                            .count());
        }
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
            redirect.addFlashAttribute("message", msg("revoke.done", pass.passNumber(), fmt.state(pass.status())));
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
    public record Row(Registration registration, Pass pass) {

        boolean matches(String needle) {
            return contains(registration.userName(), needle)
                    || contains(registration.userEmail(), needle)
                    || (pass != null && contains(pass.passNumber(), needle));
        }

        private static boolean contains(String value, String needle) {
            return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
        }
    }

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
                redirect.addFlashAttribute("error", msg("lifecycle.typeCode", current.code()));
                return confirmPage;
            }
        }
        try {
            EventDetail event = gateway.transition(id, to);
            redirect.addFlashAttribute("message", msg("lifecycle.done", event.title(), fmt.state(event.status())));
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

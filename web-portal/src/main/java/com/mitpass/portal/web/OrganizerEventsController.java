package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.web.forms.EventForm;
import com.mitpass.portal.web.forms.EventForm.SessionRow;
import com.mitpass.portal.web.support.ApiErrors;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The organizer's events: the list with its lifecycle actions, and the create/edit form (docs/plans/phase-6.md §5).
 *
 * <p>The list is one call: event-service puts {@code allowedTransitions} on each list entry (D9 row "one detail call per
 * event" replaced). The form is an {@code @ModelAttribute} {@link EventForm}; its own checks and event-service's 400
 * field errors end up in the same {@code BindingResult} ({@code @Valid}); saving redirects (PRG). "Add session" and
 * "Remove" are submit buttons that re-render the form without saving (and without showing errors yet), so the
 * sub-form works without JavaScript too.
 */
@Controller
public class OrganizerEventsController {

    /** Events still running or ahead first (soonest first), then finished ones (most recent first). */
    static final Comparator<EventSummary> ORGANIZER_ORDER = Comparator.comparing(
                    (EventSummary e) -> "COMPLETED".equals(e.status()) || "CANCELLED".equals(e.status()))
            .thenComparing((EventSummary e) -> {
                boolean done = "COMPLETED".equals(e.status()) || "CANCELLED".equals(e.status());
                long t = e.startsAt() == null ? 0 : e.startsAt().getEpochSecond();
                return done ? -t : t;
            });

    private final GatewayClient gateway;
    private final MessageSource messages;
    private final Clock clock;

    public OrganizerEventsController(GatewayClient gateway, MessageSource messages, Clock clock) {
        this.gateway = gateway;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/organizer/events")
    public String list(@AuthenticationPrincipal PortalUser user, Model model) {
        // An admin who isn't also an organizer sees every event; organizers see their own (D7 ownership).
        boolean allEvents = user.hasRole("ADMIN") && !user.hasRole("ORGANIZER");
        List<EventSummary> events = gateway.events(null, null, allEvents ? null : user.id(), 0).content().stream()
                .sorted(ORGANIZER_ORDER)
                .toList();
        model.addAttribute("events", events);
        model.addAttribute("allEvents", allEvents);
        return "organizer/events";
    }

    // ---- create ----------------------------------------------------------------------------------------------

    @GetMapping("/organizer/events/new")
    public String newEvent(Model model) {
        model.addAttribute("form", EventForm.blank(LocalDateTime.now(clock.withZone(EventForm.CAMPUS))));
        return formPage(model, null);
    }

    @PostMapping("/organizer/events")
    public String create(
            @Valid @ModelAttribute("form") EventForm form,
            BindingResult binding,
            @RequestParam(name = "_addSession", required = false) String addSession,
            @RequestParam(name = "_removeSession", required = false) Integer removeSession,
            Model model,
            RedirectAttributes redirect) {
        if (editSessions(form, addSession, removeSession)) {
            return withoutErrors(form, model, null);
        }
        if (!binding.hasErrors()) {
            try {
                EventDetail created = gateway.createEvent(form.toRequest(true));
                redirect.addFlashAttribute("message", msg("eventForm.created", created.title()));
                return "redirect:/organizer/events";
            } catch (ApiProblemException ex) {
                ApiErrors.copyInto(ex, binding);
            }
        }
        return formPage(model, null);
    }

    // ---- edit ------------------------------------------------------------------------------------------------

    @GetMapping("/organizer/events/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        EventDetail event = gateway.event(id);
        model.addAttribute("form", EventForm.of(event));
        return formPage(model, event);
    }

    @PostMapping("/organizer/events/{id}")
    public String update(
            @PathVariable long id,
            @Valid @ModelAttribute("form") EventForm form,
            BindingResult binding,
            @RequestParam(name = "_addSession", required = false) String addSession,
            @RequestParam(name = "_removeSession", required = false) Integer removeSession,
            Model model,
            RedirectAttributes redirect) {
        EventDetail event = gateway.event(id);
        if (editSessions(form, addSession, removeSession)) {
            return withoutErrors(form, model, event);
        }
        if (binding.hasErrors()) {
            return formPage(model, event);
        }
        try {
            EventDetail saved = gateway.updateEvent(id, form.toRequest(false));
            List<String> sessionProblems = saveSessions(id, form);
            if (sessionProblems.isEmpty()) {
                redirect.addFlashAttribute("message", msg("eventForm.saved", saved.title()));
            } else {
                redirect.addFlashAttribute(
                        "error", msg("eventForm.sessionsPartly", String.join("; ", sessionProblems)));
            }
            return "redirect:/organizer/events";
        } catch (ApiProblemException ex) {
            if (ex.getStatus() == 409) {
                model.addAttribute("conflict", true);
            }
            ApiErrors.copyInto(ex, binding);
            return formPage(model, event);
        }
    }

    /**
     * Sessions are separate resources in event-service: removed rows are deleted, kept rows updated, new rows added.
     * Each call stands alone, so a failure part-way is reported row by row instead of hiding what was saved.
     */
    private List<String> saveSessions(long eventId, EventForm form) {
        List<String> problems = new ArrayList<>();
        for (Long removed : form.getRemovedSessionIds()) {
            run(problems, "#" + removed, () -> gateway.deleteSession(eventId, removed));
        }
        for (SessionRow row : form.getSessions()) {
            if (row.getId() == null) {
                run(problems, row.getTitle(), () -> gateway.addSession(eventId, row.toRequest()));
            } else {
                run(problems, row.getTitle(), () -> gateway.updateSession(eventId, row.getId(), row.toRequest()));
            }
        }
        return problems;
    }

    private static void run(List<String> problems, String what, Runnable call) {
        try {
            call.run();
        } catch (ApiProblemException ex) {
            problems.add(what + ": " + ex.getMessage());
        }
    }

    /** True if the press was "Add session" or "Remove" (the form is shown again, nothing saved). */
    private static boolean editSessions(EventForm form, String add, Integer remove) {
        if (add != null) {
            LocalDateTime start = form.getStartsAt();
            LocalDateTime end = form.getEndsAt();
            form.getSessions().add(new SessionRow(null, null, start, end));
            return true;
        }
        if (remove != null && remove >= 0 && remove < form.getSessions().size()) {
            SessionRow removed = form.getSessions().remove((int) remove);
            if (removed.getId() != null) {
                form.getRemovedSessionIds().add(removed.getId());
            }
            return true;
        }
        return false;
    }

    /**
     * "Add session" and "Remove" only rearrange the form: it is shown again with a fresh, empty binding result, so
     * half-filled fields aren't flagged yet (they are validated when the form is saved).
     */
    private String withoutErrors(EventForm form, Model model, EventDetail event) {
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
        return formPage(model, event);
    }

    private String formPage(Model model, EventDetail event) {
        model.addAttribute("event", event);
        model.addAttribute("categories", gateway.categories());
        model.addAttribute("venues", gateway.venues());
        model.addAttribute("departments", departments());
        return "organizer/event-form";
    }

    private List<Department> departments() {
        try {
            return gateway.departments();
        } catch (ApiProblemException | ResourceAccessException ex) {
            return List.of();
        }
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}

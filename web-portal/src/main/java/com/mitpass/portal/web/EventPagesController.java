package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.Eligibility;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.EventQuery;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.RegisterResult;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import com.mitpass.portal.web.support.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The student's side of the catalogue (UI reference screens 2–3; docs/plans/phase-6.md §5).
 *
 * <ul>
 *   <li>{@code /events}: live search. The same URL answers a full page, or only the results fragment when HTMX asks
 *       (typing in the search box), so the page also works without JavaScript and every search can be bookmarked.
 *   <li>{@code /events/{id}}: the event, the seats bar and the student's own registration; a waitlisted registration
 *       refreshes itself until it moves.
 *   <li>Registering is a POST with an Idempotency-Key (D10), then Post/Redirect/Get to "You're in" (screen 3), which
 *       asks for the new pass every few seconds until pass-service has issued it (D9 row "new pass on reload").
 * </ul>
 */
@Controller
public class EventPagesController {

    private final GatewayClient gateway;
    private final PortalProperties properties;
    private final MessageSource messages;
    private final Clock clock;

    public EventPagesController(
            GatewayClient gateway, PortalProperties properties, MessageSource messages, Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/events")
    public String catalogue(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean past,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model) {
        EventQuery query = new EventQuery(q, category, department, from, to, past, Math.max(0, page));
        // Upcoming only, unless the student asked for past events or chose a start date.
        Page<EventSummary> events =
                gateway.search(query.effective(LocalDate.now(clock.withZone(DisplayFormat.CAMPUS))));
        model.addAttribute("query", query);
        model.addAttribute("events", events);
        model.addAttribute("prevUrl", events.hasPrevious() ? pageUrl(query, query.page() - 1) : null);
        model.addAttribute("nextUrl", events.hasNext() ? pageUrl(query, query.page() + 1) : null);
        if (Htmx.isPartial(request)) {
            return "events/list :: results";
        }
        model.addAttribute("categories", gateway.categories());
        model.addAttribute("departments", departments());
        model.addAttribute("searchDelayMillis", properties.searchDelay().toMillis());
        return "events/list";
    }

    /** A catalogue URL with the same filters on another page; empty filters are left out. */
    static String pageUrl(EventQuery query, int page) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/events");
        if (query.q() != null && !query.q().isBlank()) {
            uri.queryParam("q", query.q().strip());
        }
        if (query.category() != null && !query.category().isBlank()) {
            uri.queryParam("category", query.category());
        }
        if (query.department() != null && !query.department().isBlank()) {
            uri.queryParam("department", query.department());
        }
        if (query.from() != null) {
            uri.queryParam("from", query.from());
        }
        if (query.to() != null) {
            uri.queryParam("to", query.to());
        }
        if (query.past()) {
            uri.queryParam("past", true);
        }
        return uri.queryParam("page", page).encode().build().toUriString();
    }

    /** The filter still works if auth-service's department list can't be fetched; it just has no choices. */
    private List<Department> departments() {
        try {
            return gateway.departments();
        } catch (ApiProblemException | ResourceAccessException ex) {
            return List.of();
        }
    }

    @GetMapping("/events/{id}")
    public String detail(@PathVariable long id, @AuthenticationPrincipal PortalUser user, Model model) {
        EventDetail event = gateway.event(id);
        model.addAttribute("event", event);
        if (user != null) {
            Eligibility eligibility = gateway.eligibility(id);
            model.addAttribute("eligibility", eligibility);
            model.addAttribute("registration", eligibility.myRegistration());
        }
        // A fresh key per page view: a double click (or a browser retry) replays instead of failing (D10).
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        model.addAttribute(
                "waitlistRefreshSeconds", properties.waitlistRefresh().toSeconds());
        return "events/detail";
    }

    /**
     * The "your registration" box on the event page, polled while the registration is still waiting (PENDING or
     * WAITLISTED). HTTP 286 tells HTMX to stop polling once the registration has an outcome.
     */
    @GetMapping("/events/{id}/my-registration")
    public String myRegistration(@PathVariable long id, Model model, HttpServletResponse response) {
        Eligibility eligibility = gateway.eligibility(id);
        Registration registration = eligibility.myRegistration();
        model.addAttribute("event", gateway.event(id));
        model.addAttribute("eligibility", eligibility);
        model.addAttribute("registration", registration);
        model.addAttribute(
                "waitlistRefreshSeconds", properties.waitlistRefresh().toSeconds());
        if (registration == null || !isWaiting(registration)) {
            response.setStatus(Htmx.STOP_POLLING);
        }
        return "events/detail :: myRegistration";
    }

    @PostMapping("/events/{id}/register")
    public String register(@PathVariable long id, @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        try {
            RegisterResult result = gateway.register(id, idempotencyKey);
            Registration registration = result.registration();
            if ("CONFIRMED".equals(registration.status())) {
                return "redirect:/events/" + id + "/registered";
            }
            redirect.addFlashAttribute("message", msg("register.waitlisted", registration.waitlistPosition()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", describe(ex));
        }
        return "redirect:/events/" + id;
    }

    /** Screen 3, "You're in": what just happened, with the pass step filled in as soon as the pass exists. */
    @GetMapping("/events/{id}/registered")
    public String registered(@PathVariable long id, Model model) {
        Registration registration = gateway.eligibility(id).myRegistration();
        if (registration == null || !"CONFIRMED".equals(registration.status())) {
            return "redirect:/events/" + id;
        }
        model.addAttribute("event", gateway.event(id));
        model.addAttribute("registration", registration);
        model.addAttribute("pass", passFor(registration).orElse(null));
        model.addAttribute("pollSeconds", properties.pollInterval().toSeconds());
        model.addAttribute("tooLong", false);
        return "events/registered";
    }

    /** Polled by "You're in": the pass step. 286 (stop) once the pass is there, or when it is taking too long. */
    @GetMapping("/events/{id}/registered/pass")
    public String registeredPass(@PathVariable long id, Model model, HttpServletResponse response) {
        Registration registration = gateway.eligibility(id).myRegistration();
        Pass pass = registration == null ? null : passFor(registration).orElse(null);
        boolean tooLong = pass == null
                && registration != null
                && registration.createdAt() != null
                && registration.createdAt().plus(properties.pollTimeout()).isBefore(clock.instant());
        if (pass != null || tooLong || registration == null) {
            response.setStatus(Htmx.STOP_POLLING);
        }
        model.addAttribute("pass", pass);
        model.addAttribute("tooLong", tooLong);
        model.addAttribute("pollSeconds", properties.pollInterval().toSeconds());
        model.addAttribute("eventId", id);
        return "events/registered :: passStep";
    }

    private Optional<Pass> passFor(Registration registration) {
        return gateway.myPasses().content().stream()
                .filter(p -> registration.id().equals(p.registrationId()))
                .findFirst();
    }

    static boolean isWaiting(Registration registration) {
        return "WAITLISTED".equals(registration.status()) || "PENDING".equals(registration.status());
    }

    String describe(ApiProblemException ex) {
        if (ex.getStatus() == 403) {
            return msg("register.studentsOnly");
        }
        if (!ex.getReasons().isEmpty()) {
            return msg("register.notEligible", String.join("; ", ex.getReasons()));
        }
        return ex.getMessage();
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}

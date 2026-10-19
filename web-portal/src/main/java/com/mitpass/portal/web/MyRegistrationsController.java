package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import com.mitpass.portal.web.support.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
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
 * "My registrations" with status and waitlist position. While any registration is still waiting (PENDING or
 * WAITLISTED) the list refreshes itself with HTMX (D9 row "status on reload" replaced); it stops (HTTP 286) once
 * nothing is waiting. Cancelling returns to the page it was pressed on.
 */
@Controller
public class MyRegistrationsController {

    private final GatewayClient gateway;
    private final PortalProperties properties;
    private final MessageSource messages;

    public MyRegistrationsController(GatewayClient gateway, PortalProperties properties, MessageSource messages) {
        this.gateway = gateway;
        this.properties = properties;
        this.messages = messages;
    }

    @GetMapping("/my/registrations")
    public String list(Model model, HttpServletRequest request, HttpServletResponse response) {
        List<Registration> registrations = gateway.myRegistrations().content();
        boolean waiting = registrations.stream().anyMatch(EventPagesController::isWaiting);
        model.addAttribute("registrations", registrations);
        model.addAttribute("waiting", waiting);
        model.addAttribute(
                "waitlistRefreshSeconds", properties.waitlistRefresh().toSeconds());
        if (Htmx.isPartial(request)) {
            if (!waiting) {
                response.setStatus(Htmx.STOP_POLLING);
            }
            return "my/registrations :: list";
        }
        return "my/registrations";
    }

    @PostMapping("/my/registrations/{id}/cancel")
    public String cancel(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "/my/registrations") String back,
            RedirectAttributes redirect) {
        try {
            Registration cancelled = gateway.cancel(id);
            redirect.addFlashAttribute(
                    "message",
                    msg(cancelled.promotedRegistrationId() != null ? "cancel.doneSeatPassedOn" : "cancel.done"));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:" + PreferencesController.safeBack(back);
    }

    private String msg(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}

package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** "My registrations": status and waitlist position, with a cancel button (updates on reload; D9). */
@Controller
public class MyRegistrationsController {

    private final GatewayClient gateway;

    public MyRegistrationsController(GatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/my/registrations")
    public String list(Model model) {
        model.addAttribute("registrations", gateway.myRegistrations().content());
        return "my/registrations";
    }

    @PostMapping("/my/registrations/{id}/cancel")
    public String cancel(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Registration cancelled = gateway.cancel(id);
            redirect.addFlashAttribute(
                    "message",
                    cancelled.promotedRegistrationId() != null
                            ? "Cancelled. Your seat went to the next student on the waitlist."
                            : "Cancelled.");
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/my/registrations";
    }
}

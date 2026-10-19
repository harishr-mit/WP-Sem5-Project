package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.web.forms.AssignForm;
import com.mitpass.portal.web.forms.GateForm;
import com.mitpass.portal.web.support.ApiErrors;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The event's gates and volunteers (UI reference screen 7, "Volunteers" tab; PROJECT.md §P6 "volunteer assignment").
 * Two form objects on one page ({@link GateForm}, {@link AssignForm}); a failed one is shown again with its errors
 * while the other stays empty. Success redirects (PRG). event-service answers 422 when the address isn't a volunteer
 * and 404 when it doesn't exist; both are shown on the email field. Organizers are volunteers too (RL11), so they can
 * assign themselves.
 */
@Controller
public class StaffingPagesController {

    private final GatewayClient gateway;
    private final MessageSource messages;

    public StaffingPagesController(GatewayClient gateway, MessageSource messages) {
        this.gateway = gateway;
        this.messages = messages;
    }

    @GetMapping("/organizer/events/{id}/staff")
    public String staff(@PathVariable long id, Model model) {
        model.addAttribute("gateForm", new GateForm());
        model.addAttribute("assignForm", new AssignForm());
        return page(id, model);
    }

    @PostMapping("/organizer/events/{id}/gates")
    public String addGate(
            @PathVariable long id,
            @Valid @ModelAttribute("gateForm") GateForm form,
            BindingResult binding,
            Model model,
            RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                gateway.addGate(id, form.getCode(), form.getName());
                redirect.addFlashAttribute("message", msg("staff.gateAdded", form.getCode()));
                return "redirect:/organizer/events/" + id + "/staff";
            } catch (ApiProblemException ex) {
                ApiErrors.copyInto(ex, binding);
            }
        }
        model.addAttribute("assignForm", new AssignForm());
        return page(id, model);
    }

    @PostMapping("/organizer/events/{id}/gates/{gateId}/delete")
    public String removeGate(@PathVariable long id, @PathVariable long gateId, RedirectAttributes redirect) {
        try {
            gateway.removeGate(id, gateId);
            redirect.addFlashAttribute("message", msg("staff.gateRemoved"));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events/" + id + "/staff";
    }

    @PostMapping("/organizer/events/{id}/volunteers")
    public String assign(
            @PathVariable long id,
            @Valid @ModelAttribute("assignForm") AssignForm form,
            BindingResult binding,
            Model model,
            RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                var assigned = gateway.assignVolunteer(id, form.getEmail(), form.getGateId());
                redirect.addFlashAttribute("message", msg("staff.assigned", assigned.volunteerName()));
                return "redirect:/organizer/events/" + id + "/staff";
            } catch (ApiProblemException ex) {
                if (ex.getErrors().isEmpty() && (ex.getStatus() == 404 || ex.getStatus() == 422)) {
                    binding.rejectValue("email", "api", ex.getMessage());
                } else {
                    ApiErrors.copyInto(ex, binding);
                }
            }
        }
        model.addAttribute("gateForm", new GateForm());
        return page(id, model);
    }

    @PostMapping("/organizer/events/{id}/volunteers/{volunteerId}/delete")
    public String unassign(@PathVariable long id, @PathVariable UUID volunteerId, RedirectAttributes redirect) {
        try {
            gateway.unassignVolunteer(id, volunteerId);
            redirect.addFlashAttribute("message", msg("staff.unassigned"));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/organizer/events/" + id + "/staff";
    }

    private String page(long id, Model model) {
        model.addAttribute("event", gateway.event(id));
        model.addAttribute("gates", gateway.gates(id));
        model.addAttribute("volunteers", gateway.eventVolunteers(id));
        return "organizer/staff";
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}

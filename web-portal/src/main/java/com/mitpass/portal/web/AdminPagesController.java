package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.KeyRotation;
import com.mitpass.portal.client.ApiModels.UserProfile;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import com.mitpass.portal.web.support.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The admin's pages (PROJECT.md §P6 "Admin"): users and roles (auth-service), the system health overview (the
 * gateway's {@code /api/admin/health}, D15) and signing-key rotation. Granting ADMIN is confirmed first (RL9 style:
 * the server shows a confirmation and only acts on {@code confirmed=true}); so is rotating a key and ending sessions
 * (dialogs, with the same server check). An admin can't take ADMIN away from themselves here.
 */
@Controller
public class AdminPagesController {

    static final List<String> ROLES = List.of("STUDENT", "ORGANIZER", "VOLUNTEER", "ADMIN");

    private final GatewayClient gateway;
    private final PortalProperties properties;
    private final MessageSource messages;

    public AdminPagesController(GatewayClient gateway, PortalProperties properties, MessageSource messages) {
        this.gateway = gateway;
        this.properties = properties;
        this.messages = messages;
    }

    // ---- users ---------------------------------------------------------------------------------------------------

    @GetMapping("/admin/users")
    public String users(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model) {
        // List.of(..).contains(null) throws, and "no role chosen" is null
        model.addAttribute("users", gateway.users(q, role != null && ROLES.contains(role) ? role : null, page));
        model.addAttribute("q", q);
        model.addAttribute("role", role);
        model.addAttribute("roles", ROLES);
        return Htmx.isPartial(request) ? "admin/users :: results" : "admin/users";
    }

    @GetMapping("/admin/users/{id}")
    public String user(@PathVariable UUID id, Model model) {
        model.addAttribute("user", gateway.user(id));
        model.addAttribute("roles", ROLES);
        return "admin/user";
    }

    @PostMapping("/admin/users/{id}/roles")
    public String setRoles(
            @PathVariable UUID id,
            @RequestParam(name = "roles", required = false) List<String> wanted,
            @RequestParam(defaultValue = "false") boolean confirmed,
            @AuthenticationPrincipal PortalUser me,
            Model model,
            RedirectAttributes redirect) {
        Set<String> roles = new TreeSet<>();
        if (wanted != null) {
            wanted.stream().filter(r -> r != null && ROLES.contains(r)).forEach(roles::add);
        }
        UserProfile user = gateway.user(id);
        if (roles.isEmpty()) {
            redirect.addFlashAttribute("error", msg("admin.roles.none"));
            return "redirect:/admin/users/" + id;
        }
        if (id.equals(me.id()) && !roles.contains("ADMIN")) {
            redirect.addFlashAttribute("error", msg("admin.roles.notYourself"));
            return "redirect:/admin/users/" + id;
        }
        boolean grantsAdmin = roles.contains("ADMIN") && !user.roles().contains("ADMIN");
        if (grantsAdmin && !confirmed) {
            model.addAttribute("user", user);
            model.addAttribute("roles", ROLES);
            model.addAttribute("pendingRoles", roles);
            return "admin/user";
        }
        try {
            UserProfile saved = gateway.setRoles(id, roles);
            redirect.addFlashAttribute("message", msg("admin.roles.saved", saved.fullName()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/admin/users/{id}/unlock")
    public String unlock(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            gateway.unlock(id);
            redirect.addFlashAttribute("message", msg("admin.unlocked"));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/admin/users/{id}/sessions/end")
    public String endSessions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean confirmed,
            RedirectAttributes redirect) {
        if (!confirmed) {
            redirect.addFlashAttribute("error", msg("admin.confirmFirst"));
            return "redirect:/admin/users/" + id;
        }
        try {
            gateway.endSessions(id);
            redirect.addFlashAttribute("message", msg("admin.sessionsEnded"));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    // ---- health and keys --------------------------------------------------------------------------------------------

    /** Every registered instance; the page asks again every {@code mitpass.portal.health-refresh} (HTMX). */
    @GetMapping("/admin/health")
    public String health(HttpServletRequest request, Model model) {
        var instances = gateway.adminHealth();
        model.addAttribute("instances", instances);
        model.addAttribute("allUp", instances.stream().allMatch(i -> i.isUp()));
        model.addAttribute("refreshSeconds", properties.healthRefresh().toSeconds());
        return Htmx.isPartial(request) ? "admin/health :: board" : "admin/health";
    }

    @GetMapping("/admin/keys")
    public String keys() {
        return "admin/keys";
    }

    @PostMapping("/admin/keys/{which}/rotate")
    public String rotate(
            @PathVariable String which,
            @RequestParam(defaultValue = "false") boolean confirmed,
            RedirectAttributes redirect) {
        if (!confirmed) {
            redirect.addFlashAttribute("error", msg("admin.confirmFirst"));
            return "redirect:/admin/keys";
        }
        try {
            KeyRotation rotated = switch (which) {
                case "auth" -> gateway.rotateAuthKey();
                case "pass" -> gateway.rotatePassKey();
                default -> throw new ApiProblemException(404, null, "Unknown key " + which, null, null);
            };
            redirect.addFlashAttribute("message", msg("admin.keys.rotated", rotated.kid()));
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/keys";
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}

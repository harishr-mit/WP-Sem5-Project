package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.MyAttendance;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The two other tabs of the mockup's wallet bar (screen 4; plan Q5): <b>Stamps</b>, the attendance record with
 * certificate progress (checkin-service), and <b>Profile</b>, the account with "log out everywhere" (auth-service).
 */
@Controller
public class MyAccountController {

    private final GatewayClient gateway;

    public MyAccountController(GatewayClient gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/my/stamps")
    public String stamps(Model model) {
        List<MyAttendance> record = gateway.myAttendance().stream()
                .sorted(Comparator.comparing(
                        MyAttendance::firstCheckIn, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        model.addAttribute("record", record);
        model.addAttribute(
                "stamped", record.stream().filter(a -> a.firstCheckIn() != null).toList());
        model.addAttribute(
                "certificates",
                record.stream()
                        .filter(a -> a.sessionsRequired() != null && a.sessionsRequired() > 1)
                        .toList());
        return "my/stamps";
    }

    @GetMapping("/my/profile")
    public String profile(Model model) {
        model.addAttribute("profile", gateway.me());
        return "my/profile";
    }

    /** Ends every login of this account at auth-service, then this session too (PRG to the login page). */
    @PostMapping("/my/profile/logout-everywhere")
    public String logoutEverywhere(HttpServletRequest request, RedirectAttributes redirect) {
        try {
            gateway.logoutEverywhere();
        } catch (ApiProblemException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/my/profile";
        }
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login?loggedOut";
    }
}

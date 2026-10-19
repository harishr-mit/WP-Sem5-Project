package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.FieldError;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Home, log-in page and sign-up (Post/Redirect/Get; backend field errors land on the form fields). */
@Controller
public class AccountController {

    private final GatewayClient gateway;
    private final PortalProperties properties;

    public AccountController(GatewayClient gateway, PortalProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/events";
    }

    /** The failed attempt's message ("verify your email", "locked") comes from the session, where Security put it. */
    @GetMapping("/login")
    public String login(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        if (request.getParameter("error") != null
                && session != null
                && session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION) instanceof AuthenticationException ex) {
            model.addAttribute("loginError", ex.getMessage());
            model.addAttribute("needsVerification", ex instanceof DisabledException);
        }
        return "login";
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("form", new SignupForm());
        addDepartments(model);
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(
            @Valid @ModelAttribute("form") SignupForm form,
            BindingResult binding,
            Model model,
            RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                gateway.signup(
                        form.getFullName(),
                        form.getEmail(),
                        form.getPassword(),
                        form.getDepartment(),
                        form.getYear(),
                        form.getRegisterNumber());
                redirect.addFlashAttribute("signedUpEmail", form.getEmail());
                return "redirect:/check-email";
            } catch (ApiProblemException ex) {
                copyErrors(ex, binding);
            }
        }
        addDepartments(model);
        return "signup";
    }

    /** After sign-up (PRG): "check your inbox", with a resend form and, in dev, a link to Mailpit. */
    @GetMapping("/check-email")
    public String checkEmail(Model model) {
        model.addAttribute("mailpitUrl", properties.mailpitUrl());
        return "auth/check-email";
    }

    /**
     * The link in the verification email opens this page (D9 row). It only shows a button: the token is spent by the
     * POST, so a mail scanner or link preview that fetches the URL can't use it up.
     */
    @GetMapping("/verify-email")
    public String verifyPage(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        return "auth/verify-email";
    }

    @PostMapping("/verify-email")
    public String verify(@RequestParam String token, Model model) {
        try {
            gateway.verifyEmail(token);
            return "redirect:/login?verified";
        } catch (ApiProblemException ex) {
            model.addAttribute("token", null);
            model.addAttribute("failed", true);
            return "auth/verify-email";
        }
    }

    /** Always the same answer, so the form can't be used to find out which addresses have accounts. */
    @PostMapping("/verify-email/resend")
    public String resend(@RequestParam String email, RedirectAttributes redirect) {
        try {
            gateway.resendVerification(email.strip());
        } catch (ApiProblemException ignored) {
            // Same answer either way (see above), also for a malformed address.
        }
        redirect.addFlashAttribute("resentTo", email.strip());
        redirect.addFlashAttribute("signedUpEmail", email.strip());
        return "redirect:/check-email";
    }

    /** auth-service's 400 field errors go onto the matching fields; anything else (409, 422) is a form-level error. */
    static void copyErrors(ApiProblemException ex, BindingResult binding) {
        if (ex.getErrors().isEmpty()) {
            binding.reject("api", ex.getMessage());
            return;
        }
        BeanWrapperImpl form = new BeanWrapperImpl(binding.getTarget());
        for (FieldError error : ex.getErrors()) {
            if (error.field() != null && form.isReadableProperty(error.field())) {
                binding.rejectValue(error.field(), "api", error.message());
            } else {
                binding.reject("api", error.message());
            }
        }
    }

    private void addDepartments(Model model) {
        try {
            model.addAttribute("departments", gateway.departments());
        } catch (ApiProblemException | ResourceAccessException ex) {
            model.addAttribute("departments", java.util.List.of());
            model.addAttribute("error", "The department list is unavailable right now; please try again shortly.");
        }
    }
}

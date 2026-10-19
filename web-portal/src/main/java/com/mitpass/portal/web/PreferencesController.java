package com.mitpass.portal.web;

import com.mitpass.portal.web.support.CurrentUserInterceptor;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The light/dark switch in the header. A form post (CSRF-protected, works without JavaScript) that stores the choice in
 * a cookie and goes back to the page; the server then renders {@code data-theme}, so there is no flash of the wrong
 * theme. "system" removes the choice.
 */
@Controller
public class PreferencesController {

    @PostMapping("/preferences/theme")
    public String theme(
            @RequestParam String theme, @RequestParam(defaultValue = "/") String back, HttpServletResponse response) {
        boolean chosen = "light".equals(theme) || "dark".equals(theme);
        ResponseCookie cookie = ResponseCookie.from(CurrentUserInterceptor.THEME_COOKIE, chosen ? theme : "")
                .path("/")
                .maxAge(chosen ? Duration.ofDays(365) : Duration.ZERO)
                .sameSite("Lax")
                .httpOnly(true)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "redirect:" + safeBack(back);
    }

    /** Only a path on this site: no scheme, no "//host", no backslash tricks (open-redirect guard). */
    static String safeBack(String back) {
        if (back == null || !back.startsWith("/") || back.startsWith("//") || back.contains("\\")) {
            return "/";
        }
        return back;
    }
}

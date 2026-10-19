package com.mitpass.portal.web.support;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.config.PortalProperties;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Puts what every page's shell needs into the model (PROJECT.md §P6: "a HandlerInterceptor for the current user"):
 * {@code currentUser} (display fields only; templates never touch the tokens), {@code theme} (the user's light/dark
 * choice, null = follow the OS), {@code currentPath} (where the language and theme switches return to) and
 * {@code langLinks} (one URL per supported language, the current query kept). Redirects and non-view responses are
 * left alone.
 */
public class CurrentUserInterceptor implements HandlerInterceptor {

    public static final String THEME_COOKIE = "mitpass-theme";

    private final PortalProperties properties;

    public CurrentUserInterceptor(PortalProperties properties) {
        this.properties = properties;
    }

    @Override
    public void postHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        if (modelAndView == null || isRedirect(modelAndView)) {
            return;
        }
        decorate(modelAndView, request);
    }

    /**
     * Adds the shell attributes. Also called for error pages built by an exception handler, because Spring MVC skips
     * {@code postHandle} when the handler threw.
     */
    public void decorate(ModelAndView modelAndView, HttpServletRequest request) {
        modelAndView.addObject("currentUser", currentUser());
        modelAndView.addObject("theme", theme(request));
        String path = originalPath(request);
        modelAndView.addObject("currentPath", path);
        modelAndView.addObject("langLinks", langLinks(path, request));
    }

    public static PortalUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof PortalUser user ? user : null;
    }

    /** "light", "dark", or null when the user hasn't chosen (the CSS then follows {@code prefers-color-scheme}). */
    static String theme(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (THEME_COOKIE.equals(cookie.getName())
                    && ("light".equals(cookie.getValue()) || "dark".equals(cookie.getValue()))) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static boolean isRedirect(ModelAndView mav) {
        String name = mav.getViewName();
        return name != null && (name.startsWith("redirect:") || name.startsWith("forward:"));
    }

    /** The page the user is on; on an error page that is the path that failed, not {@code /error}. */
    static String originalPath(HttpServletRequest request) {
        Object failed = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String uri = failed instanceof String s ? s : request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private Map<String, String> langLinks(String path, HttpServletRequest request) {
        Map<String, String> links = new LinkedHashMap<>();
        boolean errorPage = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) != null;
        for (String lang : properties.supportedLocales()) {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
            if (!errorPage) {
                request.getParameterMap().forEach((name, values) -> {
                    Object[] kept = java.util.Arrays.stream(values)
                            .filter(v -> v != null && !v.isEmpty())
                            .toArray();
                    if (!"lang".equals(name) && !"_csrf".equals(name) && kept.length > 0) {
                        uri.queryParam(name, kept);
                    }
                });
            }
            links.put(lang, uri.queryParam("lang", lang).encode().build().toUriString());
        }
        return links;
    }
}

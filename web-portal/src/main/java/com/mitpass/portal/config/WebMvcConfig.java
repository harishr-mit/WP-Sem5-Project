package com.mitpass.portal.config;

import com.mitpass.portal.web.support.CurrentUserInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * MVC wiring (docs/plans/phase-6.md §4): the current-user interceptor, and i18n (D15: English and Tamil). The language
 * is chosen with {@code ?lang=ta} on any page and remembered in a cookie; without a choice the browser's language
 * decides when it is a supported one, English otherwise.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final String LANG_COOKIE = "mitpass-lang";

    private final PortalProperties properties;

    public WebMvcConfig(PortalProperties properties) {
        this.properties = properties;
    }

    @Bean
    CurrentUserInterceptor currentUserInterceptor() {
        return new CurrentUserInterceptor(properties);
    }

    @Bean
    LocaleResolver localeResolver() {
        List<Locale> supported = properties.supportedLocales().stream()
                .map(Locale::forLanguageTag)
                .toList();
        CookieLocaleResolver resolver = new CookieLocaleResolver(LANG_COOKIE);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setDefaultLocaleFunction(request -> browserChoice(request, supported));
        return resolver;
    }

    static Locale browserChoice(HttpServletRequest request, List<Locale> supported) {
        String wanted = request.getLocale().getLanguage();
        return supported.stream()
                .filter(l -> l.getLanguage().equals(wanted))
                .findFirst()
                .orElse(supported.getFirst());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SupportedLocaleChangeInterceptor(properties.supportedLocales()));
        registry.addInterceptor(currentUserInterceptor());
    }

    /** {@code ?lang=xx} for a supported language only; anything else is ignored rather than stored. */
    static class SupportedLocaleChangeInterceptor extends LocaleChangeInterceptor {

        private final List<String> supported;

        SupportedLocaleChangeInterceptor(List<String> supported) {
            this.supported = supported;
            setParamName("lang");
        }

        @Override
        public boolean preHandle(
                HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler)
                throws jakarta.servlet.ServletException {
            String lang = request.getParameter(getParamName());
            if (lang != null && !supported.contains(lang)) {
                return true;
            }
            return super.preHandle(request, response, handler);
        }
    }
}

package com.mitpass.portal.config;

import com.mitpass.portal.auth.GatewayAuthenticationProvider;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.auth.SessionExpiryFilter;
import com.mitpass.portal.auth.TokenRefresher;
import com.mitpass.portal.client.GatewayClient;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Session-based security for the portal (docs/DECISIONS.md D9): form login through auth-service, CSRF on (Thymeleaf
 * adds the token to every {@code th:action} form), the session cookie as the browser's only credential. Role rules for
 * pages live here; the services check every call again with the user's token.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain portalSecurity(
            HttpSecurity http, GatewayAuthenticationProvider provider, GatewayClient gateway, TokenRefresher refresher)
            throws Exception {
        http.authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/",
                                "/events",
                                "/events/*",
                                "/events/*/calendar.ics",
                                "/signup",
                                "/login",
                                "/check-email",
                                "/verify-email",
                                "/verify-email/resend",
                                "/preferences/**",
                                "/error",
                                "/error/**")
                        .permitAll()
                        .requestMatchers(
                                "/css/**",
                                "/js/**",
                                "/fonts/**",
                                "/webjars/**",
                                "/favicon*.svg",
                                "/favicon.ico",
                                "/scan-sw.js")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/organizer/**", "/portal-ws", "/portal-ws/**") // the live feed (P7)
                        .hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers("/scan", "/scan/**")
                        .hasAnyRole("VOLUNTEER", "ORGANIZER") // organizers are volunteers too (RL11)
                        .anyRequest()
                        .authenticated())
                .formLogin(form -> form.loginPage("/login")
                        .usernameParameter("email")
                        .defaultSuccessUrl("/events")
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/logout")
                        .addLogoutHandler(revokeRefreshToken(gateway))
                        .logoutSuccessUrl("/login?loggedOut"))
                // HTMX requests to a protected URL get HX-Redirect to the login page instead of a 302 (an XHR would
                // follow it and swap the login page into the middle of the current page).
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        (request, response, ex) -> SessionExpiryFilter.redirect(request, response, "/login"),
                        request -> "true".equals(request.getHeader(SessionExpiryFilter.HX_REQUEST))))
                .addFilterAfter(new SessionExpiryFilter(refresher), SecurityContextHolderFilter.class);
        return http.build();
    }

    /** Logging out also ends the refresh-token family at auth-service (D7). Best effort: the session ends anyway. */
    private static LogoutHandler revokeRefreshToken(GatewayClient gateway) {
        return (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof PortalUser user) {
                try {
                    gateway.logout(user.refreshToken());
                } catch (RuntimeException ex) {
                    log.warn("Couldn't revoke the refresh token at logout: {}", ex.getMessage());
                }
            }
        };
    }
}

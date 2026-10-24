package com.mitpass.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.security.Principal;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Who a rate limit counts (D17). The limits themselves are {@code RequestRateLimiter} filters on the login and scan
 * routes (application.yml, Redis token buckets). Redis down → the limiter lets requests through.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /**
     * Login: the browser's IP. Every login comes through the portal, which forwards it in {@code X-Forwarded-For}.
     * shortcut: the header is trusted from anyone (accepted for a demo, D17); trust only the portal's address in production.
     */
    @Bean
    KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just(clientIp(exchange));
    }

    /**
     * Scans: the scanner's account (JWT {@code sub}); the route needs a token, so there always is one. Primary: the
     * limiter's default, as the gateway's own principal-name resolver would be.
     */
    @Bean
    @Primary
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal().map(Principal::getName);
    }

    static String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }
}

package com.mitpass.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code mitpass.cors.allowed-origins}: browser origins allowed to call the API (the P6 web portal). */
@ConfigurationProperties("mitpass.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}

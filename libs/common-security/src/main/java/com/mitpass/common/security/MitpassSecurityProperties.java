package com.mitpass.common.security;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code mitpass.security.*}: how a service validates access tokens and which of its paths are public. */
@ConfigurationProperties("mitpass.security")
public class MitpassSecurityProperties {

    /** Where the JWK set lives. With {@link #loadBalanced}, the host is a Eureka service id. */
    private URI jwkSetUri = URI.create("http://auth-service/.well-known/jwks.json");

    /** Resolve the JWK set host through Eureka (switch off in tests or for a fixed URL). */
    private boolean loadBalanced = true;

    /** Expected {@code iss} claim. */
    private String issuer = "mitpass-auth";

    /** Required entry in the {@code aud} claim. */
    private String audience = "mitpass-api";

    /** Tolerance for {@code exp} / {@code nbf} between machines. */
    private Duration clockSkew = Duration.ofSeconds(30);

    /**
     * Paths that need no token, written {@code "METHOD /pattern"} or {@code "/pattern"} (any method). Actuator health,
     * OpenAPI and Swagger UI are always public.
     */
    private List<String> publicPaths = new ArrayList<>();

    public URI getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(URI jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public boolean isLoadBalanced() {
        return loadBalanced;
    }

    public void setLoadBalanced(boolean loadBalanced) {
        this.loadBalanced = loadBalanced;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}

package com.mitpass.auth.config;

import com.mitpass.auth.keys.SigningKeyService;
import com.mitpass.common.security.MitpassSecurityProperties;
import com.mitpass.common.security.jwt.MitpassJwtValidators;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * auth-service verifies tokens against its own keys directly, not over HTTP. Everything else (filter chain, public
 * paths, 401/403 bodies) comes from common-security; this bean only replaces its default JWKS-over-Eureka decoder.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(SigningKeyService signingKeys, MitpassSecurityProperties properties) {
        JWKSource<SecurityContext> localKeys = (selector, context) -> selector.select(signingKeys.publicJwkSet());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(localKeys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(MitpassJwtValidators.create(properties));
        return decoder;
    }
}

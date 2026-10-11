package com.mitpass.security.test;

import com.mitpass.common.security.MitpassSecurityProperties;
import com.mitpass.common.security.jwt.MitpassJwtDecoders;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Servlet tests: a {@link JwtDecoder} that trusts {@link TestJwtIssuer}'s key and applies the production claim
 * validators, so tests exercise the real verification path without auth-service or Eureka.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        return MitpassJwtDecoders.fromPublicKey(new MitpassSecurityProperties(), TestJwtIssuer.publicKey());
    }
}

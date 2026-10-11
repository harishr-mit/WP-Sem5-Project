package com.mitpass.security.test;

import com.mitpass.common.security.MitpassSecurityProperties;
import com.mitpass.common.security.jwt.MitpassJwtValidators;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** Gateway tests: the reactive counterpart of {@link TestSecurityConfig}. {@code @Primary} over the gateway's JWKS decoder. */
@TestConfiguration(proxyBeanMethods = false)
public class TestReactiveSecurityConfig {

    @Bean
    @Primary
    ReactiveJwtDecoder testReactiveJwtDecoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(TestJwtIssuer.publicKey())
                .build();
        decoder.setJwtValidator(MitpassJwtValidators.create(new MitpassSecurityProperties()));
        return decoder;
    }
}

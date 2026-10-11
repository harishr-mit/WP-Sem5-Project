package com.mitpass.common.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.security.MitpassSecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class MitpassJwtValidatorsTest {

    private final OAuth2TokenValidator<Jwt> validator = MitpassJwtValidators.create(new MitpassSecurityProperties());

    @Test
    void acceptsAWellFormedToken() {
        assertThat(validator.validate(jwt(UnaryOperator.identity())).hasErrors())
                .isFalse();
    }

    @Test
    void toleratesSmallClockSkew() {
        Instant justExpired = Instant.now().minusSeconds(10);
        assertThat(validator
                        .validate(jwt(
                                b -> b.issuedAt(justExpired.minusSeconds(900)).expiresAt(justExpired)))
                        .hasErrors())
                .isFalse();
    }

    @Test
    void rejectsExpiredWrongIssuerWrongAudienceAndMissingSubject() {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        assertThat(validator
                        .validate(jwt(b -> b.issuedAt(past).expiresAt(past.plusSeconds(900))))
                        .hasErrors())
                .isTrue();
        assertThat(validator.validate(jwt(b -> b.issuer("evil"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.audience(List.of("other")))).hasErrors())
                .isTrue();
        assertThat(validator.validate(jwt(b -> b.claims(c -> c.remove("sub")))).hasErrors())
                .isTrue();
    }

    private static Jwt jwt(UnaryOperator<Jwt.Builder> customizer) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer("mitpass-auth")
                .audience(List.of("mitpass-api"))
                .subject("00000000-0000-4000-8000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900));
        return customizer.apply(builder).build();
    }
}

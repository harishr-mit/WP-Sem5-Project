package com.mitpass.common.security.jwt;

import com.mitpass.common.security.MitpassSecurityProperties;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * The claim checks every MITPass service and the gateway apply after the signature check: {@code exp}/{@code nbf} with
 * clock skew, {@code iss}, {@code aud} and a {@code sub}.
 */
public final class MitpassJwtValidators {

    private MitpassJwtValidators() {}

    public static OAuth2TokenValidator<Jwt> create(MitpassSecurityProperties properties) {
        String audience = properties.getAudience();
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(properties.getClockSkew()),
                new JwtIssuerValidator(properties.getIssuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, Objects::nonNull)));
    }
}

package com.mitpass.common.security.jwt;

import com.mitpass.common.security.MitpassSecurityProperties;
import java.security.interfaces.RSAPublicKey;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;

/** Builds RS256 decoders with the MITPass claim validators. */
public final class MitpassJwtDecoders {

    private MitpassJwtDecoders() {}

    /** Keys from a JWK set URL. Nimbus caches the set and refetches it when a token names an unknown {@code kid}. */
    public static JwtDecoder fromJwkSetUri(MitpassSecurityProperties properties, RestOperations client) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
                        properties.getJwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(client)
                .build();
        decoder.setJwtValidator(MitpassJwtValidators.create(properties));
        return decoder;
    }

    /** A single fixed public key (tests). */
    public static JwtDecoder fromPublicKey(MitpassSecurityProperties properties, RSAPublicKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(MitpassJwtValidators.create(properties));
        return decoder;
    }
}

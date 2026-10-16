package com.mitpass.auth.service;

import com.mitpass.auth.config.ServiceClientProperties;
import com.mitpass.auth.config.ServiceClientProperties.Client;
import com.mitpass.auth.service.AccessTokenService.IssuedAccessToken;
import com.mitpass.common.web.error.ProblemException;
import com.mitpass.common.web.error.ProblemTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Issues service tokens to configured clients (docs/plans/phase-5.md §4b). Background jobs, such as checkin-service's
 * reconciler, have no user whose token they could relay (D7).
 */
@Service
public class ServiceTokenService {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenService.class);

    private final ServiceClientProperties properties;
    private final AccessTokenService accessTokens;

    public ServiceTokenService(ServiceClientProperties properties, AccessTokenService accessTokens) {
        this.properties = properties;
        this.accessTokens = accessTokens;
    }

    /** 401 {@code invalid-client} for an unknown client or a wrong secret (the same answer for both). */
    public IssuedAccessToken issue(String clientId, String secret) {
        Client client = properties.clients().get(clientId);
        // Digests compared in constant time; an unknown client costs the same comparison.
        boolean match = sameSecret(client == null ? "" : client.secret(), secret);
        if (client == null || !match) {
            log.warn("Service token refused for client '{}'", clientId);
            throw new ProblemException(
                    HttpStatus.UNAUTHORIZED,
                    ProblemTypes.of("invalid-client"),
                    "Service login failed",
                    "Unknown client or wrong secret");
        }
        return accessTokens.mintService(client.subject(), clientId, properties.tokenTtl());
    }

    private static boolean sameSecret(String expected, String given) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] a = sha.digest(expected.getBytes(StandardCharsets.UTF_8));
            byte[] b = sha.digest((given == null ? "" : given).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b) && !expected.isEmpty();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

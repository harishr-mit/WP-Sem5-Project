package com.mitpass.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code mitpass.auth.*}: token lifetimes, lockout, email verification and sign-up rules (docs/plans/phase-2.md §10). */
@ConfigurationProperties("mitpass.auth")
public record AuthProperties(
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration refreshFamilyMaxLifetime,
        Duration verificationTokenTtl,
        Duration resendInterval,
        String keySecret,
        Lockout lockout,
        String verifyLink,
        String mailFrom,
        Signup signup) {

    public record Lockout(int maxAttempts, Duration duration) {}

    public record Signup(List<DomainRule> domains) {}

    /**
     * One allowed email domain.
     *
     * @param localPartPattern regex the part before {@code @} must match, or {@code null} for any
     * @param registerNumberFromLocalPart the local part is the user's register number
     * @param canonicalizeGmail ignore dots and {@code +tags} when checking for an existing account
     */
    public record DomainRule(
            String domain, String localPartPattern, boolean registerNumberFromLocalPart, boolean canonicalizeGmail) {}
}

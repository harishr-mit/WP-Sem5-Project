package com.mitpass.auth.service;

import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.domain.RefreshToken;
import com.mitpass.auth.domain.RevokeReason;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.RefreshTokenRepository;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.common.web.error.ProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions as refresh-token families (docs/plans/phase-2.md §4).
 *
 * <ul>
 *   <li>Login starts a family. Each refresh <em>rotates</em>: the presented token is marked used and a new one is
 *       issued in the same family (sliding expiry, capped by the family's absolute lifetime).
 *   <li>Presenting a token that was already used means two parties hold it: the whole family is revoked, so the thief
 *       and the real user both have to log in again.
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final AccessTokenService accessTokens;
    private final AuthProperties properties;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenRepository tokens,
            UserRepository users,
            AccessTokenService accessTokens,
            AuthProperties properties,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** A new login: new family. The user must have roles and department loaded. */
    @Transactional
    public TokenPair startSession(User user, ClientInfo client) {
        Instant now = clock.instant();
        return issue(user, UUID.randomUUID(), now.plus(properties.refreshFamilyMaxLifetime()), client, now)
                .pair();
    }

    /**
     * Rotates a refresh token. {@code noRollbackFor}: on reuse, the family revocation must commit even though the
     * method ends with an exception (the 401).
     */
    @Transactional(noRollbackFor = ProblemException.class)
    public TokenPair refresh(String rawToken, ClientInfo client) {
        String hash = OpaqueTokens.hash(rawToken);
        Instant now = clock.instant();

        if (tokens.claim(hash, now) == 1) {
            RefreshToken presented = tokens.findByTokenHash(hash).orElseThrow(AuthProblems::invalidRefreshToken);
            User user =
                    users.findWithRolesById(presented.getUser().getId()).orElseThrow(AuthProblems::invalidRefreshToken);
            if (!user.isEnabled() || user.isLocked(now)) {
                tokens.revokeFamily(presented.getFamilyId(), now, RevokeReason.DISABLED);
                throw AuthProblems.invalidRefreshToken();
            }
            Issued next = issue(user, presented.getFamilyId(), presented.getFamilyExpiresAt(), client, now);
            presented.replacedBy(next.refreshTokenId());
            return next.pair();
        }

        RefreshToken presented = tokens.findByTokenHash(hash).orElseThrow(AuthProblems::invalidRefreshToken);
        if (presented.getUsedAt() != null) {
            int revoked = tokens.revokeFamily(presented.getFamilyId(), now, RevokeReason.REUSE);
            log.warn(
                    "Refresh token reuse: user {}, family {} ({} live tokens revoked)",
                    presented.getUser().getId(),
                    presented.getFamilyId(),
                    revoked);
            throw AuthProblems.refreshTokenReused();
        }
        throw AuthProblems.invalidRefreshToken(); // expired or revoked (logout, admin, role change)
    }

    /** Ends the login the token belongs to. Unknown or already revoked tokens are fine (idempotent). */
    @Transactional
    public void logout(String rawToken) {
        Optional<RefreshToken> token = tokens.findByTokenHash(OpaqueTokens.hash(rawToken));
        token.ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), clock.instant(), RevokeReason.LOGOUT));
    }

    /** Ends every login of a user ("log out everywhere", admin action, role change). */
    @Transactional
    public int revokeAll(UUID userId, RevokeReason reason) {
        return tokens.revokeAllForUser(userId, clock.instant(), reason);
    }

    private record Issued(TokenPair pair, UUID refreshTokenId) {}

    private Issued issue(User user, UUID familyId, Instant familyExpiresAt, ClientInfo client, Instant now) {
        String raw = OpaqueTokens.generate();
        RefreshToken token = tokens.save(new RefreshToken(
                user,
                familyId,
                OpaqueTokens.hash(raw),
                now,
                now.plus(properties.refreshTokenTtl()),
                familyExpiresAt,
                client.userAgent(),
                client.ip()));
        AccessTokenService.IssuedAccessToken access = accessTokens.mint(user);
        return new Issued(new TokenPair(access.value(), access.expiresAt(), raw, token.getExpiresAt()), token.getId());
    }
}

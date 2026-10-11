package com.mitpass.auth.repository;

import com.mitpass.auth.domain.RefreshToken;
import com.mitpass.auth.domain.RevokeReason;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Marks a token used if, and only if, it is still usable. One conditional UPDATE: of two concurrent refreshes with
     * the same token, the second blocks on the row lock, then finds {@code used_at} set and updates nothing.
     *
     * @return 1 if this caller claimed the token, 0 otherwise
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.usedAt = :now
            where t.tokenHash = :hash and t.usedAt is null and t.revokedAt is null
              and t.expiresAt > :now and t.familyExpiresAt > :now""")
    int claim(@Param("hash") String tokenHash, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason
            where t.familyId = :familyId and t.revokedAt is null""")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now, @Param("reason") RevokeReason reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason
            where t.user.id = :userId and t.revokedAt is null""")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now, @Param("reason") RevokeReason reason);
}

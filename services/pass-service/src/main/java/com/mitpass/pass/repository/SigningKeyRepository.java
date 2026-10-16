package com.mitpass.pass.repository;

import com.mitpass.pass.domain.SigningKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SigningKeyRepository extends JpaRepository<SigningKey, String> {

    Optional<SigningKey> findByStatus(SigningKey.Status status);

    /**
     * Keys whose tokens may still be valid: the active one, keys retired after {@code retiredAfter} (live tokens), and
     * keys that signed a token expiring after {@code expAfter} (static tokens). The active key first, then newest first
     * (not by time alone: a rotation can create a key in the same instant as the previous one).
     */
    @Query("""
            select k from SigningKey k
            where k.status = com.mitpass.pass.domain.SigningKey.Status.ACTIVE
               or k.retiredAt > :retiredAfter
               or k.maxTokenExp > :expAfter
            order by case when k.status = com.mitpass.pass.domain.SigningKey.Status.ACTIVE then 0 else 1 end,
                     k.createdAt desc""")
    List<SigningKey> findPublishable(Instant retiredAfter, Instant expAfter);

    /** Raises {@code max_token_exp} (never lowers it); one statement, so concurrent signers can't lose an update. */
    @Modifying
    @Query("""
            update SigningKey k set k.maxTokenExp = :exp
            where k.kid = :kid and (k.maxTokenExp is null or k.maxTokenExp < :exp)""")
    int raiseMaxTokenExp(String kid, Instant exp);
}

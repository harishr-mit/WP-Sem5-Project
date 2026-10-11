package com.mitpass.auth.repository;

import com.mitpass.auth.domain.SigningKey;
import com.mitpass.auth.domain.SigningKeyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SigningKeyRepository extends JpaRepository<SigningKey, String> {

    Optional<SigningKey> findByStatus(SigningKeyStatus status);

    /** The JWK set: the active key plus keys retired recently enough that tokens they signed may still be valid. */
    List<SigningKey> findByStatusOrRetiredAtAfterOrderByCreatedAtDesc(SigningKeyStatus status, Instant retiredAfter);
}

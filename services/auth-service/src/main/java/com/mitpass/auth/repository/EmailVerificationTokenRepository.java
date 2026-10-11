package com.mitpass.auth.repository;

import com.mitpass.auth.domain.EmailVerificationToken;
import com.mitpass.auth.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    Optional<EmailVerificationToken> findFirstByUserOrderByCreatedAtDesc(User user);
}

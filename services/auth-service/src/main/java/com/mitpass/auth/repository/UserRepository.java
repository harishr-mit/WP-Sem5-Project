package com.mitpass.auth.repository;

import com.mitpass.auth.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /** Login and lookups: roles and department are needed to mint a token, so fetch them in the same query. */
    @EntityGraph(attributePaths = {"roles", "department"})
    Optional<User> findByCanonicalEmail(String canonicalEmail);

    @EntityGraph(attributePaths = {"roles", "department"})
    Optional<User> findWithRolesById(UUID id);

    boolean existsByCanonicalEmail(String canonicalEmail);

    boolean existsByRegisterNumber(String registerNumber);

    /** Admins who can still log in: the last one can't be banned (RL10). */
    @Query("select count(u) from User u join u.roles r where r.name = com.mitpass.auth.domain.RoleName.ADMIN"
            + " and u.enabled = true")
    long countEnabledAdmins();
}

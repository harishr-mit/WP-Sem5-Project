package com.mitpass.auth.repository;

import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Filters for the admin user list. A {@code null} filter adds no predicate. */
public final class UserSpecifications {

    private UserSpecifications() {}

    public static Specification<User> search(String q, RoleName role) {
        List<Specification<User>> specs = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("fullName")), like),
                    cb.like(root.get("registerNumber"), like)));
        }
        if (role != null) {
            specs.add((root, query, cb) -> {
                query.distinct(true);
                return cb.equal(root.join("roles", JoinType.INNER).get("name"), role);
            });
        }
        return specs.isEmpty() ? Specification.unrestricted() : Specification.allOf(specs);
    }
}

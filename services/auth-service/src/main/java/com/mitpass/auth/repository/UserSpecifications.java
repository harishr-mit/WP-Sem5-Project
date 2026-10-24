package com.mitpass.auth.repository;

import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for the admin user list. A {@code null} filter adds no predicate. Text goes through {@code search_text} and
 * its trigram index (V5, D17); with text, results are ordered by similarity, best match first.
 */
public final class UserSpecifications {

    private UserSpecifications() {}

    public static Specification<User> search(String q, RoleName role) {
        List<Specification<User>> specs = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String text = q.trim().toLowerCase(Locale.ROOT);
            specs.add((root, query, cb) -> {
                if (query.getResultType() != Long.class) { // not on the count query
                    query.orderBy(
                            cb.desc(cb.function("similarity", Double.class, root.get("searchText"), cb.literal(text))));
                }
                return cb.like(root.get("searchText"), "%" + escapeLike(text) + "%", '\\');
            });
        }
        if (role != null) {
            // EXISTS rather than join + DISTINCT: a DISTINCT query can't be ordered by similarity().
            specs.add((root, query, cb) -> {
                Subquery<Integer> withRole = query.subquery(Integer.class);
                Root<User> same = withRole.from(User.class);
                withRole.select(cb.literal(1))
                        .where(
                                cb.equal(same, root),
                                cb.equal(same.join("roles", JoinType.INNER).get("name"), role));
                return cb.exists(withRole);
            });
        }
        return specs.isEmpty() ? Specification.unrestricted() : Specification.allOf(specs);
    }

    /** {@code %} and {@code _} typed by the admin are literal. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

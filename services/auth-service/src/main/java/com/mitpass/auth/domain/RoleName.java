package com.mitpass.auth.domain;

/** The four user roles; names match the {@code roles} claim and {@code com.mitpass.common.security.Roles}. */
public enum RoleName {
    STUDENT,
    ORGANIZER,
    VOLUNTEER,
    ADMIN;

    /**
     * The roles a user effectively holds: an ORGANIZER is always a VOLUNTEER too (REVIEW-LATER RL11, owner 2026-10-16:
     * in a college the organizers are mostly the people who check others in). Applied whenever roles are set.
     */
    public static java.util.Set<RoleName> withImplied(java.util.Set<RoleName> roles) {
        java.util.EnumSet<RoleName> all =
                roles.isEmpty() ? java.util.EnumSet.noneOf(RoleName.class) : java.util.EnumSet.copyOf(roles);
        if (all.contains(ORGANIZER)) {
            all.add(VOLUNTEER);
        }
        return all;
    }
}

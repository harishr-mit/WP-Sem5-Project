package com.mitpass.common.security;

import java.util.List;

/** The four MITPass user roles (plus {@link #SERVICE} for service tokens). Tokens carry them in the {@code roles} claim; Spring sees them as {@code ROLE_<name>}. */
public final class Roles {

    public static final String STUDENT = "STUDENT";
    public static final String ORGANIZER = "ORGANIZER";
    public static final String VOLUNTEER = "VOLUNTEER";
    public static final String ADMIN = "ADMIN";

    public static final List<String> ALL = List.of(STUDENT, ORGANIZER, VOLUNTEER, ADMIN);

    /**
     * Not a user role: only service tokens carry it, which auth-service gives background jobs (P5, D7). Not in
     * {@link #ALL}; an endpoint allows it explicitly.
     */
    public static final String SERVICE = "SERVICE";

    private Roles() {}

    public static String authority(String role) {
        return "ROLE_" + role;
    }
}

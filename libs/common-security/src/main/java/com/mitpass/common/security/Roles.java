package com.mitpass.common.security;

import java.util.List;

/** The four MITPass roles. Tokens carry them in the {@code roles} claim; Spring sees them as {@code ROLE_<name>}. */
public final class Roles {

    public static final String STUDENT = "STUDENT";
    public static final String ORGANIZER = "ORGANIZER";
    public static final String VOLUNTEER = "VOLUNTEER";
    public static final String ADMIN = "ADMIN";

    public static final List<String> ALL = List.of(STUDENT, ORGANIZER, VOLUNTEER, ADMIN);

    private Roles() {}

    public static String authority(String role) {
        return "ROLE_" + role;
    }
}

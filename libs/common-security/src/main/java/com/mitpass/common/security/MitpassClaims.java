package com.mitpass.common.security;

/** Claim names in a MITPass access token (docs/plans/phase-2.md §4). */
public final class MitpassClaims {

    public static final String ROLES = "roles";
    public static final String EMAIL = "email";
    public static final String NAME = "name";
    public static final String DEPARTMENT = "dept";
    public static final String YEAR = "year";
    public static final String REGISTER_NUMBER = "regNo";

    private MitpassClaims() {}
}

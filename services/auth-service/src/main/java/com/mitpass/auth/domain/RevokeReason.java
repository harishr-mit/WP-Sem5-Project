package com.mitpass.auth.domain;

/** Why a refresh-token family was ended. */
public enum RevokeReason {
    /** The user logged out (or ended all their sessions). */
    LOGOUT,
    /** A used token was presented again: likely stolen. */
    REUSE,
    /** An admin ended the user's sessions. */
    ADMIN,
    /** The user's roles changed; the next login gets a token with the new ones. */
    ROLE_CHANGE,
    /** The account can no longer log in. */
    DISABLED
}

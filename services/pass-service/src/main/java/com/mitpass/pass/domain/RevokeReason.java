package com.mitpass.pass.domain;

/** Why a pass was revoked; travels in {@code PassRevoked} and the revocation feed. */
public enum RevokeReason {
    /** The event's organizer revoked it. */
    ORGANIZER,
    /** An admin revoked it. */
    ADMIN,
    /** The student (or organizer) cancelled the registration. */
    REGISTRATION_CANCELLED,
    /** The whole event was cancelled (saga step, PROJECT.md §P4). */
    EVENT_CANCELLED
}

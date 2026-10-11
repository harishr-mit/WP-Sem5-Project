package com.mitpass.auth.domain;

/** ACTIVE signs new tokens (at most one); RETIRED stays in the JWKS until tokens it signed have expired. */
public enum SigningKeyStatus {
    ACTIVE,
    RETIRED
}

package com.mitpass.common.pass;

import com.nimbusds.jose.JOSEObjectType;

/**
 * The pass-token contract (docs/DECISIONS.md D12): a compact JWS, ES256, header {@code typ: "mitpass-pass+jws"},
 * claims {@code iss, pid, eid, sub, iat, exp, jti, knd}. pass-service signs, checkin-service and the offline scanner
 * (P7) verify. Claims are only ever added.
 */
public final class PassTokens {

    public static final JOSEObjectType TYPE = new JOSEObjectType("mitpass-pass+jws");
    public static final String DEFAULT_ISSUER = "mitpass-pass";

    public static final String PASS_ID = "pid";
    public static final String EVENT_ID = "eid";
    public static final String KIND = "knd";
    /** Reserved for per-session passes; not issued yet. */
    public static final String SESSION_ID = "sid";

    /** {@code knd} values. */
    public static final String KIND_LIVE = "L";

    public static final String KIND_STATIC = "S";

    private PassTokens() {}
}

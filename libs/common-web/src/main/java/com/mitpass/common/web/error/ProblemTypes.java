package com.mitpass.common.web.error;

import java.net.URI;

/** The {@code type} URIs used in every MITPass {@link org.springframework.http.ProblemDetail} (RFC 9457). */
public final class ProblemTypes {

    public static final URI VALIDATION = of("validation");
    public static final URI MALFORMED_REQUEST = of("malformed-request");
    public static final URI NOT_FOUND = of("not-found");
    public static final URI CONFLICT = of("conflict");
    public static final URI BUSINESS_RULE = of("business-rule");
    public static final URI INTERNAL = of("internal");

    private ProblemTypes() {}

    public static URI of(String slug) {
        return URI.create("urn:mitpass:problem:" + slug);
    }
}

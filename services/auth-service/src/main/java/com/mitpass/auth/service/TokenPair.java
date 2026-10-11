package com.mitpass.auth.service;

import java.time.Instant;

/** What a login or refresh hands back: a short-lived access token and the next refresh token. */
public record TokenPair(
        String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
        return "TokenPair[accessTokenExpiresAt=" + accessTokenExpiresAt + "]";
    }
}

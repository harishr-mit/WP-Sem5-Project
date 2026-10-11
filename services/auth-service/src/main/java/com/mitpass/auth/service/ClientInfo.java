package com.mitpass.auth.service;

/** Where a login or refresh came from, kept on the refresh token for the user's "sessions" view and audits. */
public record ClientInfo(String userAgent, String ip) {

    public ClientInfo {
        userAgent = truncate(userAgent, 200);
        ip = truncate(ip, 45);
    }

    public static ClientInfo unknown() {
        return new ClientInfo(null, null);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

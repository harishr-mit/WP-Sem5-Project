package com.mitpass.notification;

import com.mitpass.notification.client.PassMedia;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** pass-service in memory: every pass has a (tiny) PNG unless its event allows only the live code. */
public class FakePassMedia implements PassMedia {

    /** The 8-byte PNG signature: enough for a CID image in a test. */
    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final Set<UUID> liveOnly = ConcurrentHashMap.newKeySet();

    public void liveOnly(UUID passId) {
        liveOnly.add(passId);
    }

    @Override
    public Optional<byte[]> staticQrPng(UUID passId) {
        return liveOnly.contains(passId) ? Optional.empty() : Optional.of(PNG);
    }
}

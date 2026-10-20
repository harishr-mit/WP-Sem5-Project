package com.mitpass.notification.client;

import java.util.Optional;
import java.util.UUID;

/** The pass's static QR code from pass-service, for the pass email (SERVICE token; D16). */
public interface PassMedia {

    /** PNG bytes, or empty when the event allows only the live code (pass-service answers 409). */
    Optional<byte[]> staticQrPng(UUID passId);
}

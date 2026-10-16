package com.mitpass.checkin.domain;

/** How the pass was presented: the rotating live QR, the static PDF/email QR, or a typed pass number. */
public enum ScanMethod {
    QR_LIVE,
    QR_STATIC,
    MANUAL
}

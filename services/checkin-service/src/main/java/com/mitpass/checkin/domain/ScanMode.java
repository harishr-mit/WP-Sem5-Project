package com.mitpass.checkin.domain;

/** What the volunteer is doing: letting people in, letting them out, or looking a pass number up (manual entry). */
public enum ScanMode {
    IN,
    OUT,
    LOOKUP
}

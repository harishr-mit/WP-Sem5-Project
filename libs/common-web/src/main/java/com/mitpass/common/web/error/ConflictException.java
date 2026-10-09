package com.mitpass.common.web.error;

/** The request clashes with the current state of the resource (duplicate key, wrong lifecycle state). Mapped to {@code 409}. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

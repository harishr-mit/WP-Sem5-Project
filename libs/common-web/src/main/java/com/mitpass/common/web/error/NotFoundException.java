package com.mitpass.common.web.error;

/** The addressed resource does not exist. Mapped to {@code 404}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String resource, Object id) {
        return new NotFoundException(resource + " " + id + " was not found");
    }
}

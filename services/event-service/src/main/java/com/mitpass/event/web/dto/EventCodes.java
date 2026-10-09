package com.mitpass.event.web.dto;

/** Same rule as the {@code ck_event_code} check constraint in V1__init.sql. */
final class EventCodes {

    static final String PATTERN = "^[A-Z0-9]{3,16}$";
    static final String MESSAGE = "must be 3-16 uppercase letters or digits";

    private EventCodes() {}
}

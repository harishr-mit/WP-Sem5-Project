package com.mitpass.event.web.dto;

/** Same rules as the {@code ck_event_code} (V1) and {@code ck_eligible_department_code} (V4) check constraints. */
final class EventCodes {

    static final String PATTERN = "^[A-Z0-9]{3,16}$";
    static final String MESSAGE = "must be 3-16 uppercase letters or digits";

    static final String DEPARTMENT_PATTERN = "^[A-Z]{2,16}$";
    static final String DEPARTMENT_MESSAGE = "must be a department code: 2-16 uppercase letters";

    private EventCodes() {}
}

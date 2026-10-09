package com.mitpass.common.web.error;

/**
 * The payload is well-formed but breaks a domain rule that needs the database to check (for example, event capacity
 * larger than the venue). Mapped to {@code 422}.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}

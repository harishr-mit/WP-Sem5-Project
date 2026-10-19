package com.mitpass.portal.web.forms;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new gate for an event, with event-service's rules: a code of 2-16 of A-Z, 0-9 and - (upper-cased on the way in) and
 * a name.
 */
public class GateForm {

    @NotBlank
    @Pattern(regexp = "^[A-Z0-9-]{2,16}$", message = "{gateForm.code.pattern}")
    private String code;

    @NotBlank
    @Size(max = 80)
    private String name;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code == null ? null : code.strip().toUpperCase(java.util.Locale.ROOT);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

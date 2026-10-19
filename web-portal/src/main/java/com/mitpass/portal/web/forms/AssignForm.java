package com.mitpass.portal.web.forms;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Assign a volunteer by email; {@code gateId} empty = any gate of the event. */
public class AssignForm {

    @NotBlank
    @Email
    private String email;

    private Long gateId;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.strip();
    }

    public Long getGateId() {
        return gateId;
    }

    public void setGateId(Long gateId) {
        this.gateId = gateId;
    }
}

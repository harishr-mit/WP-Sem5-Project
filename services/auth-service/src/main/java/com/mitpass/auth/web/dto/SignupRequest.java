package com.mitpass.auth.web.dto;

import com.mitpass.auth.validation.StrongPassword;
import com.mitpass.auth.validation.ValidSignup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Sign-up form (UI reference screen 1). New accounts are students; an admin grants other roles. */
@ValidSignup
public record SignupRequest(
        @Schema(example = "Priya Raman") @NotBlank @Size(max = 120)
        String fullName,

        @Schema(example = "2026509032@student.annauniv.edu") @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(example = "Techfest2026") @NotNull @StrongPassword
        String password,

        @Schema(example = "CSE", description = "Department code, see GET /api/departments") @NotBlank @Size(max = 16)
        String department,

        @Schema(example = "1") @Min(1) @Max(5) Integer year,

        @Schema(
                example = "2026509032",
                description = "Optional. Taken from the email for @student.annauniv.edu addresses")
        @Pattern(regexp = "\\d{10}(\\d{2})?", message = "must be 10 or 12 digits")
        String registerNumber) {

    /** Never log the password. */
    @Override
    public String toString() {
        return "SignupRequest[email=%s, department=%s, year=%s]".formatted(email, department, year);
    }
}

package com.mitpass.auth.web.dto;

import com.mitpass.auth.domain.RoleName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** The small request bodies of the auth API. Records holding secrets override {@code toString}. */
public final class AuthRequests {

    private AuthRequests() {}

    public record LoginRequest(
            @Schema(example = "organizer.a@mitpass.test") @NotBlank @Size(max = 254)
            String email,

            @Schema(example = "Passw0rd!") @NotBlank @Size(max = 200)
            String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[***]";
        }
    }

    public record VerifyEmailRequest(
            @NotBlank @Size(max = 100) String token) {

        @Override
        public String toString() {
            return "VerifyEmailRequest[***]";
        }
    }

    public record ResendVerificationRequest(
            @NotBlank @Size(max = 254) String email) {}

    public record RoleUpdateRequest(
            @Schema(example = "[\"STUDENT\", \"VOLUNTEER\"]") @NotNull @NotEmpty
            Set<@NotNull RoleName> roles) {}
}

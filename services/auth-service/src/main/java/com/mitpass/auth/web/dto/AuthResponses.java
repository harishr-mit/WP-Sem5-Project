package com.mitpass.auth.web.dto;

import com.mitpass.auth.domain.Department;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.service.TokenPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of the auth API. */
public final class AuthResponses {

    private AuthResponses() {}

    /** OAuth2-style token response: lifetimes in seconds. */
    public record TokenResponse(
            String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {

        public static TokenResponse from(TokenPair pair, Instant now) {
            return new TokenResponse(
                    pair.accessToken(),
                    "Bearer",
                    secondsUntil(now, pair.accessTokenExpiresAt()),
                    pair.refreshToken(),
                    secondsUntil(now, pair.refreshTokenExpiresAt()));
        }

        /** Rounded up: a token minted a few milliseconds ago still reports its full 900 s. */
        private static long secondsUntil(Instant now, Instant expiresAt) {
            return (Duration.between(now, expiresAt).toMillis() + 999) / 1000;
        }

        @Override
        public String toString() {
            return "TokenResponse[expiresIn=" + expiresIn + "]";
        }
    }

    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String department,
            Integer year,
            String registerNumber,
            List<RoleName> roles,
            boolean emailVerified,
            boolean locked,
            Instant createdAt,
            Instant lastLoginAt) {

        /** Needs roles and department loaded. */
        public static UserResponse from(User user, Instant now) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getFullName(),
                    user.getDepartment().getCode(),
                    user.getYearOfStudy(),
                    user.getRegisterNumber(),
                    List.copyOf(user.roleNames()),
                    user.isEmailVerified(),
                    user.isLocked(now),
                    user.getCreatedAt(),
                    user.getLastLoginAt());
        }
    }

    /** What an organizer sees when looking a volunteer up by email. */
    public record UserSummary(UUID id, String fullName, String email, List<RoleName> roles) {

        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getFullName(), user.getEmail(), List.copyOf(user.roleNames()));
        }
    }

    public record VerificationResponse(String status) {}

    public record KeyRotationResponse(String kid) {}

    public record DepartmentResponse(String code, String name) {

        public static DepartmentResponse from(Department department) {
            return new DepartmentResponse(department.getCode(), department.getName());
        }
    }
}

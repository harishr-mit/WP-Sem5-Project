package com.mitpass.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.common.security.jwt.MitpassJwtAuthenticationConverter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class CurrentUserTest {

    private final UUID id = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsEveryClaim() {
        CurrentUser user = CurrentUser.from(jwt(List.of("STUDENT", "VOLUNTEER")));

        assertThat(user.id()).isEqualTo(id);
        assertThat(user.email()).isEqualTo("2026509032@student.annauniv.edu");
        assertThat(user.name()).isEqualTo("Priya Raman");
        assertThat(user.roles()).containsExactlyInAnyOrder("STUDENT", "VOLUNTEER");
        assertThat(user.department()).isEqualTo("CSE");
        assertThat(user.year()).isEqualTo(3);
        assertThat(user.registerNumber()).isEqualTo("2026509032");
        assertThat(user.hasRole("VOLUNTEER")).isTrue();
        assertThat(user.hasAnyRole(List.of("ADMIN", "ORGANIZER"))).isFalse();
        assertThat(user.toString()).doesNotContain("token-value");
    }

    @Test
    void converterMapsRolesToAuthoritiesAndSubToName() {
        var authentication = new MitpassJwtAuthenticationConverter().convert(jwt(List.of("ORGANIZER")));

        assertThat(authentication.getName()).isEqualTo(id.toString());
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ORGANIZER");
    }

    @Test
    void currentReadsTheSecurityContext() {
        assertThat(CurrentUser.current()).isEmpty();

        SecurityContextHolder.getContext()
                .setAuthentication(new MitpassJwtAuthenticationConverter().convert(jwt(List.of("ADMIN"))));

        assertThat(CurrentUser.current())
                .hasValueSatisfying(user -> assertThat(user.id()).isEqualTo(id));
    }

    private Jwt jwt(List<String> roles) {
        return Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .subject(id.toString())
                .claim("email", "2026509032@student.annauniv.edu")
                .claim("name", "Priya Raman")
                .claim("roles", roles)
                .claim("dept", "CSE")
                .claim("year", 3)
                .claim("regNo", "2026509032")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}

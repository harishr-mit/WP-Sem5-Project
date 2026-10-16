package com.mitpass.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** RL11 (owner, 2026-10-16): ORGANIZER implies VOLUNTEER; nothing else is implied. */
class RoleNameTest {

    @Test
    void anOrganizerIsAlsoAVolunteer() {
        assertThat(RoleName.withImplied(Set.of(RoleName.ORGANIZER)))
                .containsExactlyInAnyOrder(RoleName.ORGANIZER, RoleName.VOLUNTEER);
        assertThat(RoleName.withImplied(Set.of(RoleName.ORGANIZER, RoleName.VOLUNTEER)))
                .containsExactlyInAnyOrder(RoleName.ORGANIZER, RoleName.VOLUNTEER);
    }

    @Test
    void otherRolesImplyNothing() {
        assertThat(RoleName.withImplied(Set.of(RoleName.STUDENT))).containsExactly(RoleName.STUDENT);
        assertThat(RoleName.withImplied(Set.of(RoleName.ADMIN))).containsExactly(RoleName.ADMIN);
        assertThat(RoleName.withImplied(Set.of())).isEmpty();
    }
}

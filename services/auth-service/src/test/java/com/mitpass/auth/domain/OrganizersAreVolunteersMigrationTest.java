package com.mitpass.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mitpass.auth.AuthIntegrationTest;
import com.mitpass.auth.TestAccounts;
import com.mitpass.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V4 (RL11) gives VOLUNTEER to organizers that existed before the rule; running it again changes nothing. */
@AuthIntegrationTest
class OrganizersAreVolunteersMigrationTest {

    @Autowired
    TestAccounts accounts;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Test
    void existingOrganizersBecomeVolunteers() throws Exception {
        User organizer = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.ORGANIZER); // created directly
        User student = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT);
        String v4 = new ClassPathResource("db/migration/V4__organizers_are_volunteers.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        jdbc.sql(v4).update();
        jdbc.sql(v4).update();

        assertThat(users.findWithRolesById(organizer.getId()).orElseThrow().roleNames())
                .containsExactlyInAnyOrder(RoleName.ORGANIZER, RoleName.VOLUNTEER);
        assertThat(users.findWithRolesById(student.getId()).orElseThrow().roleNames())
                .containsExactly(RoleName.STUDENT);
    }
}

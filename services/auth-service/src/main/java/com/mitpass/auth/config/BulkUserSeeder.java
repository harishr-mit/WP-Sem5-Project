package com.mitpass.auth.config;

import com.mitpass.seed.SeedPeople;
import com.mitpass.seed.SeedPeople.Person;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code bulk-seed} profile (docs/DECISIONS.md D17): a college's worth of verified accounts, so the demo and the load
 * test have real people to log in. {@link SeedPeople} makes the same students event-service registers. Everyone has the
 * demo password (D8: the same bcrypt hash as {@code R__demo_users.sql}), students get STUDENT, volunteers STUDENT and
 * VOLUNTEER. Inserts directly into the table, like the demo seed, so it sends no emails. Safe to run on every start.
 */
@Component
@Profile("bulk-seed")
public class BulkUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BulkUserSeeder.class);

    /** {@code Passw0rd!}, as in R__demo_users.sql. */
    static final String PASSWORD_HASH = "{bcrypt}$2a$10$ROLDbllstk6lg0i.Vy8VhOMKc2kSLTt4RD4tFyZn5aqbKnvq985Xe";

    private final JdbcTemplate jdbc;
    private final int students;
    private final int volunteers;

    public BulkUserSeeder(
            JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${mitpass.seed.bulk.students:2000}") int students,
            @org.springframework.beans.factory.annotation.Value("${mitpass.seed.bulk.volunteers:40}") int volunteers) {
        this.jdbc = jdbc;
        this.students = students;
        this.volunteers = volunteers;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Person> people = new java.util.ArrayList<>(SeedPeople.students(students));
        people.addAll(SeedPeople.volunteers(volunteers));
        jdbc.batchUpdate("""
                INSERT INTO app_user (id, email, canonical_email, full_name, department_code, year_of_study,
                                      register_number, password_hash, email_verified_at, enabled, created_at, created_by,
                                      updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), TRUE, now(), 'seed', now(), 'seed')
                ON CONFLICT (id) DO NOTHING""", people, 500, (ps, p) -> {
            ps.setObject(1, p.id());
            ps.setString(2, p.email());
            ps.setString(3, p.email());
            ps.setString(4, p.fullName());
            ps.setString(5, p.department());
            ps.setShort(6, (short) p.year());
            ps.setString(7, p.registerNumber());
            ps.setString(8, PASSWORD_HASH);
        });
        jdbc.batchUpdate(
                "INSERT INTO user_role (user_id, role_id) SELECT ?, r.id FROM role r WHERE r.name = ?"
                        + " ON CONFLICT DO NOTHING",
                roles(people),
                500,
                (ps, pair) -> {
                    ps.setObject(1, pair.userId());
                    ps.setString(2, pair.role());
                });
        log.info("Bulk seed: {} students and {} volunteers (password {})", students, volunteers, SeedPeople.PASSWORD);
    }

    private record UserRole(java.util.UUID userId, String role) {}

    private static List<UserRole> roles(List<Person> people) {
        List<UserRole> roles = new java.util.ArrayList<>();
        for (Person p : people) {
            roles.add(new UserRole(p.id(), "STUDENT"));
            if (p.volunteer()) {
                roles.add(new UserRole(p.id(), "VOLUNTEER"));
            }
        }
        return roles;
    }
}

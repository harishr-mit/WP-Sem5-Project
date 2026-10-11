package com.mitpass.auth;

import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.DepartmentRepository;
import com.mitpass.auth.repository.RoleRepository;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.auth.service.EmailPolicy;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates verified accounts directly in the database (bypassing sign-up), for tests that start at login. */
@Component
public class TestAccounts {

    public static final String PASSWORD = "Passw0rd!";

    private final UserRepository users;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final PasswordEncoder passwordEncoder;
    private final EmailPolicy emailPolicy;

    public TestAccounts(
            UserRepository users,
            RoleRepository roles,
            DepartmentRepository departments,
            PasswordEncoder passwordEncoder,
            EmailPolicy emailPolicy) {
        this.emailPolicy = emailPolicy;
        this.users = users;
        this.roles = roles;
        this.departments = departments;
        this.passwordEncoder = passwordEncoder;
    }

    public static String uniqueGmail() {
        return "test." + UUID.randomUUID().toString().substring(0, 12) + "@gmail.com";
    }

    @Transactional
    public User verifiedUser(String email, RoleName... roleNames) {
        User user = new User(
                email,
                emailPolicy.canonicalize(email),
                "Test " + roleNames[0],
                departments.findById("CSE").orElseThrow(),
                2,
                null,
                passwordEncoder.encode(PASSWORD));
        for (RoleName role : roleNames) {
            user.addRole(roles.findByName(role).orElseThrow());
        }
        user.markEmailVerified(Instant.now());
        return users.save(user);
    }
}

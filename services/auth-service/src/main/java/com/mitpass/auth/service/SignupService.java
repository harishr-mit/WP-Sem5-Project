package com.mitpass.auth.service;

import com.mitpass.auth.domain.Department;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.DepartmentRepository;
import com.mitpass.auth.repository.RoleRepository;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.auth.web.dto.SignupRequest;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a student account and sends the verification email (after commit). */
@Service
public class SignupService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final EmailPolicy emailPolicy;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService verification;

    public SignupService(
            UserRepository users,
            RoleRepository roles,
            DepartmentRepository departments,
            EmailPolicy emailPolicy,
            PasswordEncoder passwordEncoder,
            EmailVerificationService verification) {
        this.users = users;
        this.roles = roles;
        this.departments = departments;
        this.emailPolicy = emailPolicy;
        this.passwordEncoder = passwordEncoder;
        this.verification = verification;
    }

    /** The request has passed Bean Validation (including the email policy); these checks need the database. */
    @Transactional
    public User signup(SignupRequest request) {
        EmailPolicy.Evaluation email = emailPolicy.evaluate(request.email());
        if (!email.allowed()) {
            throw new BusinessRuleException("Email " + email.error());
        }
        if (users.existsByCanonicalEmail(email.canonicalEmail())) {
            throw new ConflictException("An account with this email already exists");
        }
        String registerNumber = email.registerNumber() != null ? email.registerNumber() : blankToNull(request);
        if (registerNumber != null && users.existsByRegisterNumber(registerNumber)) {
            throw new ConflictException("An account with register number " + registerNumber + " already exists");
        }
        String departmentCode = request.department().trim().toUpperCase(Locale.ROOT);
        Department department = departments
                .findById(departmentCode)
                .orElseThrow(() -> new BusinessRuleException("Unknown department '" + departmentCode + "'"));

        User user = new User(
                email.email(),
                email.canonicalEmail(),
                request.fullName().trim(),
                department,
                request.year(),
                registerNumber,
                passwordEncoder.encode(request.password()));
        user.addRole(roles.findByName(RoleName.STUDENT).orElseThrow());
        users.save(user);
        verification.issueFor(user);
        return user;
    }

    private static String blankToNull(SignupRequest request) {
        String value = request.registerNumber();
        return value == null || value.isBlank() ? null : value.trim();
    }
}

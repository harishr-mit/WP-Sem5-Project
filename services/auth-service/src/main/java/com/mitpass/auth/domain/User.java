package com.mitpass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.UuidGenerator;

/** A MITPass account. Login state (failed attempts, lock) lives here too, so it changes in the same transaction. */
@Entity
@Table(name = "app_user")
public class User extends AuditableEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 254, unique = true)
    private String canonicalEmail;

    @Column(nullable = false, length = 120)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_code")
    private Department department;

    @Column(name = "year_of_study")
    private Short yearOfStudy;

    @Column(length = 12, unique = true)
    private String registerNumber;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    private Instant emailVerifiedAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int failedLoginAttempts;

    private Instant lockedUntil;

    private Instant lastLoginAt;

    @Version
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected User() {}

    public User(
            String email,
            String canonicalEmail,
            String fullName,
            Department department,
            Integer yearOfStudy,
            String registerNumber,
            String passwordHash) {
        this.email = email;
        this.canonicalEmail = canonicalEmail;
        this.fullName = fullName;
        this.department = department;
        this.yearOfStudy = yearOfStudy == null ? null : yearOfStudy.shortValue();
        this.registerNumber = registerNumber;
        this.passwordHash = passwordHash;
    }

    // ---- login state ------------------------------------------------------------------------------------------

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Counts a wrong password; the {@code maxAttempts}-th in a row locks the account for {@code lockFor}. */
    public void recordFailedLogin(int maxAttempts, Duration lockFor, Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockFor);
            failedLoginAttempts = 0;
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void unlock() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
        }
    }

    // ---- roles ------------------------------------------------------------------------------------------------

    public void addRole(Role role) {
        roles.add(role);
    }

    public void replaceRoles(Set<Role> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    public boolean hasRole(RoleName name) {
        return roles.stream().anyMatch(role -> role.getName() == name);
    }

    public Set<RoleName> roleNames() {
        return roles.stream().map(Role::getName).collect(Collectors.toCollection(() -> EnumSet.noneOf(RoleName.class)));
    }

    // ---- accessors --------------------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getCanonicalEmail() {
        return canonicalEmail;
    }

    public String getFullName() {
        return fullName;
    }

    public Department getDepartment() {
        return department;
    }

    public Integer getYearOfStudy() {
        return yearOfStudy == null ? null : yearOfStudy.intValue();
    }

    public String getRegisterNumber() {
        return registerNumber;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Set<Role> getRoles() {
        return roles;
    }
}

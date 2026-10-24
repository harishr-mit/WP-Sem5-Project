package com.mitpass.auth.service;

import com.mitpass.auth.domain.RevokeReason;
import com.mitpass.auth.domain.Role;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.mitpass.auth.repository.RoleRepository;
import com.mitpass.auth.repository.UserRepository;
import com.mitpass.auth.repository.UserSpecifications;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.NotFoundException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading users and the admin actions on them. */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final EmailPolicy emailPolicy;
    private final RefreshTokenService sessions;

    public UserAdminService(
            UserRepository users, RoleRepository roles, EmailPolicy emailPolicy, RefreshTokenService sessions) {
        this.users = users;
        this.roles = roles;
        this.emailPolicy = emailPolicy;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findWithRolesById(id).orElseThrow(() -> NotFoundException.of("User", id));
    }

    /** Roles are loaded in batches ({@code @BatchSize} on {@code User.roles}), not one query per user. */
    @Transactional(readOnly = true)
    public Page<User> search(String q, RoleName role, Pageable pageable) {
        // With text the order is relevance (the specification's similarity), so the page's own sort is dropped.
        Pageable paging =
                q == null || q.isBlank() ? pageable : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<User> page = users.findAll(UserSpecifications.search(q, role), paging);
        page.forEach(user -> {
            user.getRoles().size();
            user.getDepartment().getCode();
        });
        return page;
    }

    @Transactional(readOnly = true)
    public User lookupByEmail(String email) {
        return users.findByCanonicalEmail(emailPolicy.canonicalize(email))
                .orElseThrow(() -> new NotFoundException("No user with email " + email));
    }

    /**
     * Replaces the user's roles (plus implied ones: an ORGANIZER is also a VOLUNTEER, RL11) and ends their sessions, so the next token they get carries the new roles. An admin
     * can't remove their own ADMIN role (there would be nobody left to give it back).
     */
    @Transactional
    public User setRoles(UUID id, Set<RoleName> newRoles, UUID actingAdmin) {
        if (id.equals(actingAdmin) && !newRoles.contains(RoleName.ADMIN)) {
            throw new BusinessRuleException("You can't remove your own ADMIN role");
        }
        User user = get(id);
        Set<Role> resolved = new HashSet<>(roles.findByNameIn(RoleName.withImplied(newRoles)));
        user.replaceRoles(resolved);
        users.saveAndFlush(user);
        sessions.revokeAll(id, RevokeReason.ROLE_CHANGE);
        return get(id);
    }

    /**
     * Ban or unban an account (RL10, D17). A ban ends every session at once; an access token already issued runs out
     * within its lifetime (15 min). Nobody can ban themselves or the last admin who can still log in.
     */
    @Transactional
    public User setBanned(UUID id, boolean banned, UUID actingAdmin) {
        User user = get(id);
        if (banned) {
            if (id.equals(actingAdmin)) {
                throw new BusinessRuleException("You can't ban yourself");
            }
            if (user.hasRole(RoleName.ADMIN) && user.isEnabled() && users.countEnabledAdmins() <= 1) {
                throw new BusinessRuleException("You can't ban the last admin");
            }
        }
        user.setEnabled(!banned);
        users.saveAndFlush(user);
        if (banned) {
            sessions.revokeAll(id, RevokeReason.DISABLED);
        }
        return get(id);
    }

    @Transactional
    public void unlock(UUID id) {
        get(id).unlock();
    }

    @Transactional
    public int revokeSessions(UUID id, RevokeReason reason) {
        get(id);
        return sessions.revokeAll(id, reason);
    }
}

package com.mitpass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One refresh token. Every login starts a family; each refresh marks the presented token used and issues the next one
 * in the same family. Presenting a token that was already used means it was copied: the whole family is revoked.
 */
@Entity
public class RefreshToken {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private UUID familyId;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Absolute end of the login: rotation never extends a family past this. */
    @Column(nullable = false)
    private Instant familyExpiresAt;

    private Instant usedAt;

    private UUID replacedBy;

    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private RevokeReason revokeReason;

    @Column(length = 200)
    private String userAgent;

    @Column(length = 45)
    private String ip;

    protected RefreshToken() {}

    public RefreshToken(
            User user,
            UUID familyId,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            Instant familyExpiresAt,
            String userAgent,
            String ip) {
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt.isAfter(familyExpiresAt) ? familyExpiresAt : expiresAt;
        this.familyExpiresAt = familyExpiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    public void replacedBy(UUID next) {
        this.replacedBy = next;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getFamilyExpiresAt() {
        return familyExpiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RevokeReason getRevokeReason() {
        return revokeReason;
    }
}

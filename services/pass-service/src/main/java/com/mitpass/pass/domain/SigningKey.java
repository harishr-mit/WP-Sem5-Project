package com.mitpass.pass.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * An ES256 key pair for pass tokens (D12). The private half is stored encrypted (common-security
 * {@code KeyEncryptor}). {@code maxTokenExp} is the latest expiry this key has signed, so a retired key stays in the
 * JWK set exactly as long as a token it signed can still be valid.
 */
@Entity
public class SigningKey {

    public enum Status {
        ACTIVE,
        RETIRED
    }

    @Id
    @Column(length = 48)
    private String kid;

    @Column(nullable = false, length = 8)
    private String algorithm;

    @Column(nullable = false, columnDefinition = "text")
    private String publicJwk;

    @Column(nullable = false, columnDefinition = "text")
    private String privateKeyEnc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant retiredAt;

    private Instant maxTokenExp;

    protected SigningKey() {}

    public SigningKey(String kid, String algorithm, String publicJwk, String privateKeyEnc, Instant createdAt) {
        this.kid = kid;
        this.algorithm = algorithm;
        this.publicJwk = publicJwk;
        this.privateKeyEnc = privateKeyEnc;
        this.status = Status.ACTIVE;
        this.createdAt = createdAt;
    }

    public void retire(Instant now) {
        status = Status.RETIRED;
        retiredAt = now;
    }

    public String getKid() {
        return kid;
    }

    public String getPublicJwk() {
        return publicJwk;
    }

    public String getPrivateKeyEnc() {
        return privateKeyEnc;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public Instant getMaxTokenExp() {
        return maxTokenExp;
    }
}

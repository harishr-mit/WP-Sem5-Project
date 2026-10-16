package com.mitpass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;

/** An RS256 key pair for access tokens. The private half is stored encrypted (common-security {@code KeyEncryptor}). */
@Entity
public class SigningKey {

    @Id
    @Column(length = 40)
    private String kid;

    @Column(nullable = false, length = 8)
    private String algorithm;

    @Column(nullable = false, columnDefinition = "text")
    private String publicJwk;

    @Column(nullable = false, columnDefinition = "text")
    private String privateKeyEnc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private SigningKeyStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant retiredAt;

    protected SigningKey() {}

    public SigningKey(String kid, String algorithm, String publicJwk, String privateKeyEnc, Instant createdAt) {
        this.kid = kid;
        this.algorithm = algorithm;
        this.publicJwk = publicJwk;
        this.privateKeyEnc = privateKeyEnc;
        this.status = SigningKeyStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    public void retire(Instant now) {
        status = SigningKeyStatus.RETIRED;
        retiredAt = now;
    }

    public String getKid() {
        return kid;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getPublicJwk() {
        return publicJwk;
    }

    public String getPrivateKeyEnc() {
        return privateKeyEnc;
    }

    public SigningKeyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }
}

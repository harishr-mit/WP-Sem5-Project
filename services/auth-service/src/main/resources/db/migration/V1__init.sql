-- auth-service schema, Phase 2 (docs/plans/phase-2.md §5).
-- Flyway owns the schema; Hibernate runs with ddl-auto=validate against it.

CREATE TABLE department (
    code VARCHAR(16)  PRIMARY KEY,
    name VARCHAR(120) NOT NULL
);

CREATE TABLE role (
    id   SMALLINT    PRIMARY KEY,
    name VARCHAR(16) NOT NULL,
    CONSTRAINT uk_role_name UNIQUE (name),
    CONSTRAINT ck_role_name CHECK (name IN ('STUDENT', 'ORGANIZER', 'VOLUNTEER', 'ADMIN'))
);

CREATE TABLE app_user (
    id                    UUID         PRIMARY KEY,
    email                 VARCHAR(254) NOT NULL,
    -- Lower-cased; for Gmail also without dots and +tags. One account per inbox, and the login lookup key.
    canonical_email       VARCHAR(254) NOT NULL,
    full_name             VARCHAR(120) NOT NULL,
    department_code       VARCHAR(16)  NOT NULL REFERENCES department (code),
    year_of_study         SMALLINT,
    register_number       VARCHAR(12),
    password_hash         VARCHAR(100) NOT NULL,
    email_verified_at     TIMESTAMPTZ,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(64)  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(64)  NOT NULL,
    CONSTRAINT uk_user_canonical_email UNIQUE (canonical_email),
    CONSTRAINT uk_user_register_number UNIQUE (register_number),
    CONSTRAINT ck_user_year CHECK (year_of_study BETWEEN 1 AND 5),
    CONSTRAINT ck_user_register_number CHECK (register_number ~ '^[0-9]{10}([0-9]{2})?$'),
    CONSTRAINT ck_user_failed_logins CHECK (failed_login_attempts >= 0)
);

CREATE TABLE user_role (
    user_id UUID     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES role (id),
    PRIMARY KEY (user_id, role_id)
);

-- Only SHA-256 hashes of the random tokens are stored; the raw value exists only in the email / the client.
CREATE TABLE email_verification_token (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_verification_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_verification_token_user ON email_verification_token (user_id);

-- One row per refresh token. Tokens of one login share a family_id; rotation marks the old row used and points
-- replaced_by at the new one. Presenting a used token revokes the whole family (reuse detection).
CREATE TABLE refresh_token (
    id                UUID        PRIMARY KEY,
    user_id           UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id         UUID        NOT NULL,
    token_hash        VARCHAR(64) NOT NULL,
    issued_at         TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    family_expires_at TIMESTAMPTZ NOT NULL,
    used_at           TIMESTAMPTZ,
    replaced_by       UUID,
    revoked_at        TIMESTAMPTZ,
    revoke_reason     VARCHAR(24),
    user_agent        VARCHAR(200),
    ip                VARCHAR(45),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_reason CHECK (revoke_reason IN ('LOGOUT', 'REUSE', 'ADMIN', 'ROLE_CHANGE', 'DISABLED'))
);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);

-- RS256 signing keys. The private JWK is encrypted at rest (AES-GCM, key from MITPASS_KEY_SECRET).
CREATE TABLE signing_key (
    kid             VARCHAR(40) PRIMARY KEY,
    algorithm       VARCHAR(8)  NOT NULL,
    public_jwk      TEXT        NOT NULL,
    private_key_enc TEXT        NOT NULL,
    status          VARCHAR(8)  NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    retired_at      TIMESTAMPTZ,
    CONSTRAINT ck_signing_key_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);
-- At most one ACTIVE key, even if two instances create one at the same moment.
CREATE UNIQUE INDEX uk_signing_key_one_active ON signing_key (status) WHERE status = 'ACTIVE';

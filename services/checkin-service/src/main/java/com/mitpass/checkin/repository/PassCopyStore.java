package com.mitpass.checkin.repository;

import com.mitpass.checkin.domain.PassRef;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The pass copy (D14), written only from the owner's data: messages, the reconciler and the single-pass fetch. Upserts
 * are idempotent and tolerate any order. REVOKED is sticky: nothing turns it back into VALID, as in pass-service.
 */
@Repository
public class PassCopyStore {

    private static final String UPSERT = """
            INSERT INTO pass_ref (pass_id, pass_number, event_id, registration_id, user_id, holder_name, holder_email,
                                  status, issued_at, revoked_at, revoke_reason, updated_at)
            VALUES (:passId, :number, :eventId, :registrationId, :userId, :holderName, :holderEmail,
                    :status, :issuedAt, :revokedAt, :revokeReason, :now)
            ON CONFLICT (pass_id) DO UPDATE SET
                pass_number     = COALESCE(EXCLUDED.pass_number, pass_ref.pass_number),
                event_id        = EXCLUDED.event_id,
                registration_id = COALESCE(EXCLUDED.registration_id, pass_ref.registration_id),
                user_id         = COALESCE(EXCLUDED.user_id, pass_ref.user_id),
                holder_name     = COALESCE(EXCLUDED.holder_name, pass_ref.holder_name),
                holder_email    = COALESCE(EXCLUDED.holder_email, pass_ref.holder_email),
                status          = CASE WHEN pass_ref.status = 'REVOKED' THEN 'REVOKED' ELSE EXCLUDED.status END,
                issued_at       = COALESCE(pass_ref.issued_at, EXCLUDED.issued_at),
                revoked_at      = COALESCE(pass_ref.revoked_at, EXCLUDED.revoked_at),
                revoke_reason   = COALESCE(pass_ref.revoke_reason, EXCLUDED.revoke_reason),
                updated_at      = EXCLUDED.updated_at
            """;

    private final JdbcClient jdbc;

    public PassCopyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A pass as the owner describes it now ({@code PassIssued}, a sync page, the single-pass fetch). */
    public void upsert(PassRef pass, Instant now) {
        jdbc.sql(UPSERT)
                .param("passId", pass.passId())
                .param("number", pass.passNumber())
                .param("eventId", pass.eventId())
                .param("registrationId", pass.registrationId())
                .param("userId", pass.userId())
                .param("holderName", pass.holderName())
                .param("holderEmail", pass.holderEmail())
                .param("status", pass.revoked() ? PassRef.REVOKED : PassRef.VALID)
                .param("issuedAt", ts(pass.issuedAt()))
                .param("revokedAt", ts(pass.revokedAt()))
                .param("revokeReason", pass.revokeReason())
                .param("now", ts(now))
                .update();
    }

    /** A revocation ({@code PassRevoked} or the feed). Creates a bare row if the pass's details haven't arrived. */
    public void markRevoked(
            UUID passId, long eventId, String passNumber, Instant revokedAt, String reason, Instant now) {
        upsert(
                new PassRef(
                        passId, passNumber, eventId, null, null, null, null, PassRef.REVOKED, null, revokedAt, reason),
                now);
    }

    public Optional<PassRef> find(UUID passId) {
        return jdbc.sql("SELECT * FROM pass_ref WHERE pass_id = :id")
                .param("id", passId)
                .query(PassCopyStore::map)
                .optional();
    }

    public Optional<PassRef> findByNumber(String passNumber) {
        return jdbc.sql("SELECT * FROM pass_ref WHERE pass_number = :number")
                .param("number", passNumber)
                .query(PassCopyStore::map)
                .optional();
    }

    private static PassRef map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PassRef(
                rs.getObject("pass_id", UUID.class),
                rs.getString("pass_number"),
                rs.getLong("event_id"),
                rs.getObject("registration_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("holder_name"),
                rs.getString("holder_email"),
                rs.getString("status"),
                instant(rs.getTimestamp("issued_at")),
                instant(rs.getTimestamp("revoked_at")),
                rs.getString("revoke_reason"));
    }

    static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

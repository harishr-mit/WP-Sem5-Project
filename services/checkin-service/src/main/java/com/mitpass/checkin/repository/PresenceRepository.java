package com.mitpass.checkin.repository;

import com.mitpass.checkin.domain.Presence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PresenceRepository extends JpaRepository<Presence, UUID> {

    /** Creates the NOT_ARRIVED row if there is none yet; concurrent callers don't fail (ON CONFLICT DO NOTHING). */
    @Modifying
    @Query(value = """
                    INSERT INTO presence (pass_id, event_id, state, session_id, changed_at, version)
                    VALUES (:passId, :eventId, 'NOT_ARRIVED', 0, :at, 0)
                    ON CONFLICT (pass_id) DO NOTHING
                    """, nativeQuery = true)
    int createIfMissing(
            @Param("passId") UUID passId, @Param("eventId") long eventId, @Param("at") java.time.Instant at);

    /** {@code SELECT … FOR UPDATE}: the second of two simultaneous scans waits here until the first commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Presence p WHERE p.passId = :passId")
    Optional<Presence> lockById(@Param("passId") UUID passId);
}

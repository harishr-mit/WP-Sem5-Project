package com.mitpass.event.repository;

import com.mitpass.event.domain.Registration;
import com.mitpass.event.domain.RegistrationChange;
import com.mitpass.event.domain.RegistrationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationRepository extends JpaRepository<Registration, UUID> {

    @EntityGraph(attributePaths = "event")
    Optional<Registration> findWithEventById(UUID id);

    @Query("""
            select count(r) > 0 from Registration r
            where r.event.id = :eventId and r.userId = :userId
              and r.status <> com.mitpass.event.domain.RegistrationStatus.CANCELLED""")
    boolean existsActive(@Param("eventId") long eventId, @Param("userId") UUID userId);

    @EntityGraph(attributePaths = "event")
    @Query("""
            select r from Registration r
            where r.event.id = :eventId and r.userId = :userId
              and r.status <> com.mitpass.event.domain.RegistrationStatus.CANCELLED""")
    Optional<Registration> findActive(@Param("eventId") long eventId, @Param("userId") UUID userId);

    /**
     * Head of the waitlist, locked: a concurrent cancellation can't promote the same student. Native for
     * {@code FOR UPDATE} with {@code LIMIT}; returns the id so the entity is then loaded normally.
     */
    @Query(
            value = "SELECT id FROM registration WHERE event_id = :eventId AND status = 'WAITLISTED'"
                    + " ORDER BY waitlist_seq LIMIT 1 FOR UPDATE",
            nativeQuery = true)
    Optional<UUID> lockFirstWaitlisted(@Param("eventId") long eventId);

    @Query(value = "SELECT nextval('registration_waitlist_seq')", nativeQuery = true)
    long nextWaitlistSeq();

    /** 1-based position: how many WAITLISTED registrations of the event are at or ahead of {@code seq}. */
    @Query("""
            select count(r) from Registration r
            where r.event.id = :eventId
              and r.status = com.mitpass.event.domain.RegistrationStatus.WAITLISTED
              and r.waitlistSeq <= :seq""")
    long waitlistPosition(@Param("eventId") long eventId, @Param("seq") long seq);

    @Query("""
            select count(r) from Registration r
            where r.event.id = :eventId and r.status = :status""")
    long countByEventAndStatus(@Param("eventId") long eventId, @Param("status") RegistrationStatus status);

    @EntityGraph(attributePaths = "event")
    @Query("""
            select r from Registration r
            where r.userId = :userId and (:status is null or r.status = :status)""")
    Page<Registration> findByUser(
            @Param("userId") UUID userId, @Param("status") RegistrationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "event")
    @Query("""
            select r from Registration r
            where r.event.id = :eventId and (:status is null or r.status = :status)""")
    Page<Registration> findByEvent(
            @Param("eventId") long eventId, @Param("status") RegistrationStatus status, Pageable pageable);

    @Query("select r.userId from Registration r where r.id = :id")
    Optional<UUID> findUserIdById(@Param("id") UUID id);

    @Query("select r.event.id from Registration r where r.id = :id")
    Optional<Long> findEventIdById(@Param("id") UUID id);

    @Query("""
            select c from RegistrationChange c
            where c.registration.id = :id
            order by c.changedAt, c.id""")
    List<RegistrationChange> findHistory(@Param("id") UUID id);
}

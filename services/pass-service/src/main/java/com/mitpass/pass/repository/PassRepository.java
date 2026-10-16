package com.mitpass.pass.repository;

import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PassRepository extends JpaRepository<Pass, UUID> {

    /** A pass id with its event, for the scheduler's per-event batches. */
    record PassRef(UUID id, Long eventId) {}

    @EntityGraph(attributePaths = "event")
    Optional<Pass> findDetailedById(UUID id);

    Optional<Pass> findByRegistrationId(UUID registrationId);

    boolean existsByRegistrationId(UUID registrationId);

    @EntityGraph(attributePaths = "event")
    Page<Pass> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "event")
    Page<Pass> findByUserIdAndStatus(UUID userId, PassStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "event")
    Page<Pass> findByEvent_Id(Long eventId, Pageable pageable);

    @EntityGraph(attributePaths = "event")
    Page<Pass> findByEvent_IdAndStatus(Long eventId, PassStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "event")
    List<Pass> findByEvent_IdAndStatusIn(Long eventId, Collection<PassStatus> statuses);

    @Query("select p.userId from Pass p where p.id = :id")
    Optional<UUID> findUserIdById(UUID id);

    @Query("select p.event.id from Pass p where p.id = :id")
    Optional<Long> findEventIdById(UUID id);

    /** ISSUED passes whose live code has unlocked: the event starts at or before {@code startsBefore}. */
    @Query("""
            select new com.mitpass.pass.repository.PassRepository$PassRef(p.id, e.id)
            from Pass p join p.event e
            where p.status = com.mitpass.pass.domain.PassStatus.ISSUED and e.startsAt <= :startsBefore
              and e.endsAt > :endsAfter
            order by e.id""")
    List<PassRef> findDueForActivation(Instant startsBefore, Instant endsAfter);

    /** Valid passes of events that ended at or before {@code endsBefore}. */
    @Query("""
            select new com.mitpass.pass.repository.PassRepository$PassRef(p.id, e.id)
            from Pass p join p.event e
            where p.status in (com.mitpass.pass.domain.PassStatus.ISSUED, com.mitpass.pass.domain.PassStatus.ACTIVE)
              and e.endsAt <= :endsBefore
            order by e.id""")
    List<PassRef> findDueForExpiry(Instant endsBefore);
}

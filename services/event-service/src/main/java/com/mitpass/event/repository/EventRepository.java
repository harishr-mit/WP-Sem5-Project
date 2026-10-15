package com.mitpass.event.repository;

import com.mitpass.event.domain.Event;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Fetch plans per use case (the entity maps every association LAZY):
 *
 * <ul>
 *   <li>search/list: {@code category + venue} in the same query, no sessions (fetching a collection while paginating
 *       would make Hibernate paginate in memory);
 *   <li>detail: {@code category + venue + sessions}.
 * </ul>
 */
public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    @Override
    @EntityGraph(attributePaths = {"category", "venue"})
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);

    /** One bag ({@code sessions}) plus one set ({@code eligibleDepartments}) is fine to fetch together. */
    @EntityGraph(attributePaths = {"category", "venue", "sessions", "eligibleDepartments"})
    Optional<Event> findDetailedById(Long id);

    /** Ownership check for {@code @eventSecurity}: one column, no entity loaded. */
    @Query("select e.organizerId from Event e where e.id = :id")
    Optional<UUID> findOrganizerIdById(@Param("id") Long id);

    // ---- due moves for EventLifecycleScheduler (ids only; each move then loads its event in its own transaction) ----

    /** PUBLISHED, opening time reached, and registration would still be open (closing time and start ahead). */
    @Query("""
            select e.id from Event e
            where e.status = com.mitpass.event.domain.EventStatus.PUBLISHED
              and e.registrationOpensAt <= :now
              and (e.registrationClosesAt is null or e.registrationClosesAt > :now)
              and e.startsAt > :now""")
    List<Long> findIdsDueToOpen(@Param("now") Instant now);

    /** REGISTRATION_OPEN and the closing time (or, without one, the start) reached. */
    @Query("""
            select e.id from Event e
            where e.status = com.mitpass.event.domain.EventStatus.REGISTRATION_OPEN
              and coalesce(e.registrationClosesAt, e.startsAt) <= :now""")
    List<Long> findIdsDueToClose(@Param("now") Instant now);

    /** REGISTRATION_CLOSED and the start lies before {@code liveFrom} (= now + doors-open lead). */
    @Query("""
            select e.id from Event e
            where e.status = com.mitpass.event.domain.EventStatus.REGISTRATION_CLOSED
              and e.startsAt <= :liveFrom""")
    List<Long> findIdsDueToStart(@Param("liveFrom") Instant liveFrom);

    @Query("""
            select e.id from Event e
            where e.status = com.mitpass.event.domain.EventStatus.LIVE
              and e.endsAt <= :now""")
    List<Long> findIdsDueToComplete(@Param("now") Instant now);

    // ---- seat counter (docs/DECISIONS.md D10). Native SQL: seats_taken is read-only in the entity mapping. ----

    /**
     * The fast path of a registration: takes a seat only if one is left and registration is open. The row lock this
     * UPDATE takes is held until commit, so concurrent claims on one event queue up instead of overbooking.
     *
     * @return 1 if a seat was taken, 0 if the event is full (or not open)
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE event SET seats_taken = seats_taken + 1"
                    + " WHERE id = :id AND status = 'REGISTRATION_OPEN' AND seats_taken < capacity",
            nativeQuery = true)
    int claimSeat(@Param("id") long id);

    /** A seat for a waitlist promotion: also allowed after registration closed, until the event goes LIVE. */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE event SET seats_taken = seats_taken + 1"
                    + " WHERE id = :id AND status IN ('REGISTRATION_OPEN', 'REGISTRATION_CLOSED')"
                    + " AND seats_taken < capacity",
            nativeQuery = true)
    int claimSeatForPromotion(@Param("id") long id);

    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE event SET seats_taken = seats_taken - 1 WHERE id = :id AND seats_taken > 0",
            nativeQuery = true)
    int releaseSeat(@Param("id") long id);

    /** The committed counter, bypassing any stale copy in the persistence context. */
    @Query(value = "SELECT seats_taken FROM event WHERE id = :id", nativeQuery = true)
    int findSeatsTakenById(@Param("id") long id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}

package com.mitpass.event.repository;

import com.mitpass.event.domain.VolunteerAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VolunteerAssignmentRepository extends JpaRepository<VolunteerAssignment, Long> {

    @EntityGraph(attributePaths = {"event", "gate"})
    List<VolunteerAssignment> findByEventIdOrderByVolunteerName(Long eventId);

    /** A volunteer's own assignments, soonest event first (the scanner's gate picker). */
    @EntityGraph(attributePaths = {"event", "gate"})
    List<VolunteerAssignment> findByVolunteerIdOrderByEventStartsAt(UUID volunteerId);

    Optional<VolunteerAssignment> findByEventIdAndVolunteerId(Long eventId, UUID volunteerId);

    boolean existsByEventIdAndVolunteerId(Long eventId, UUID volunteerId);
}

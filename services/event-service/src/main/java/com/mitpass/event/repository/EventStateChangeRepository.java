package com.mitpass.event.repository;

import com.mitpass.event.domain.EventStateChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventStateChangeRepository extends JpaRepository<EventStateChange, Long> {

    /** Oldest first; the id breaks ties between changes in the same instant. */
    List<EventStateChange> findByEventIdOrderByChangedAtAscIdAsc(Long eventId);
}

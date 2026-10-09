package com.mitpass.event.repository;

import com.mitpass.event.domain.EventSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSessionRepository extends JpaRepository<EventSession, Long> {

    List<EventSession> findByEventIdOrderByStartsAtAsc(Long eventId);

    Optional<EventSession> findByIdAndEventId(Long id, Long eventId);
}

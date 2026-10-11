package com.mitpass.event.repository;

import com.mitpass.event.domain.EventGate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventGateRepository extends JpaRepository<EventGate, Long> {

    List<EventGate> findByEventIdOrderByCode(Long eventId);

    Optional<EventGate> findByIdAndEventId(Long id, Long eventId);

    boolean existsByEventIdAndCode(Long eventId, String code);
}

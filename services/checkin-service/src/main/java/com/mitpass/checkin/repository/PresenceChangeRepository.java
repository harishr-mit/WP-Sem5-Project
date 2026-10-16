package com.mitpass.checkin.repository;

import com.mitpass.checkin.domain.PresenceChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PresenceChangeRepository extends JpaRepository<PresenceChange, Long> {

    List<PresenceChange> findByPassIdOrderById(UUID passId);
}

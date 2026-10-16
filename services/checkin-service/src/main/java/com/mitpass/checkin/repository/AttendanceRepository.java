package com.mitpass.checkin.repository;

import com.mitpass.checkin.domain.Attendance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    Optional<Attendance> findByPassIdAndSessionId(UUID passId, long sessionId);

    List<Attendance> findByPassIdOrderByCheckedInAt(UUID passId);
}

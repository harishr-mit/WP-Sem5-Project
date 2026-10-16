package com.mitpass.checkin.repository;

import com.mitpass.checkin.domain.ScanLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanLogRepository extends JpaRepository<ScanLog, UUID> {

    long countByPassId(UUID passId);
}

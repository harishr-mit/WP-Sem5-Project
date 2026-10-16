package com.mitpass.checkin.service;

import com.mitpass.checkin.domain.ScanLog;
import com.mitpass.checkin.repository.ScanLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the scan log row of a rejected scan in its own short transaction, so the refusal is on record even when
 * nothing else changed (or the check-in transaction rolled back).
 */
@Component
public class ScanLogWriter {

    private final ScanLogRepository logs;

    public ScanLogWriter(ScanLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(ScanLog log) {
        logs.save(log);
    }
}

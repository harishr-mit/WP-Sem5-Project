package com.mitpass.checkin.service;

import com.mitpass.checkin.domain.ScanResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The offline columns of the scan log (V2): which phone-side id a row answers, so a resent sync is answered from here,
 * and the real time it reached the server (the pipeline ran with the phone's time).
 */
@Component
public class OfflineScanLog {

    private static final Logger log = LoggerFactory.getLogger(OfflineScanLog.class);

    private final JdbcClient jdbc;

    public OfflineScanLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScanResult> result(String deviceId, String localId) {
        return jdbc.sql("SELECT result FROM scan_log WHERE device_id = :device AND local_id = :local")
                .param("device", deviceId)
                .param("local", localId)
                .query(String.class)
                .optional()
                .map(ScanResult::valueOf);
    }

    public void mark(UUID scanId, String localId, ScanResult result, Instant received) {
        try {
            jdbc.sql("""
                            UPDATE scan_log SET local_id = :local, offline = true, result = :result, received_at = :received
                            WHERE id = :id
                            """)
                    .param("local", localId)
                    .param("result", result.name())
                    .param("received", Timestamp.from(received))
                    .param("id", scanId)
                    .update();
        } catch (DataIntegrityViolationException e) {
            // two syncs of the same queue raced: the first one's row answers later resends
            log.info("Offline scan {} was already recorded by a parallel sync", localId);
        }
    }
}

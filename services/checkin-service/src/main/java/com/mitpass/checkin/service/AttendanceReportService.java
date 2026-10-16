package com.mitpass.checkin.service;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.client.EventCatalog.SessionInfo;
import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.web.dto.AttendanceDtos.AttendanceSummary;
import com.mitpass.checkin.web.dto.AttendanceDtos.AttendeeRow;
import com.mitpass.checkin.web.dto.AttendanceDtos.CertificateRow;
import com.mitpass.checkin.web.dto.AttendanceDtos.GateCount;
import com.mitpass.checkin.web.dto.AttendanceDtos.MyAttendance;
import com.mitpass.checkin.web.dto.AttendanceDtos.SessionCount;
import com.mitpass.common.web.paging.PageResponse;
import java.io.IOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance reports (docs/plans/phase-5.md §4 "Reports"): SQL aggregates over this service's own tables, with event
 * titles and sessions from event-service. Read-only.
 */
@Service
@Transactional(readOnly = true)
public class AttendanceReportService {

    private static final String ROWS = """
            SELECT p.pass_id, p.pass_number, p.holder_name, p.holder_email, p.status,
                   COALESCE(a.sessions, 0) AS sessions, a.first_in, a.last_gate,
                   COALESCE(pr.state, 'NOT_ARRIVED') AS presence
            FROM pass_ref p
            LEFT JOIN (SELECT pass_id, count(*) AS sessions, min(checked_in_at) AS first_in,
                              (array_agg(gate_code ORDER BY checked_in_at DESC))[1] AS last_gate
                       FROM attendance WHERE event_id = :event GROUP BY pass_id) a ON a.pass_id = p.pass_id
            LEFT JOIN presence pr ON pr.pass_id = p.pass_id
            WHERE p.event_id = :event AND p.pass_number IS NOT NULL
              AND (CAST(:presence AS VARCHAR) IS NULL OR COALESCE(pr.state, 'NOT_ARRIVED') = :presence)
            ORDER BY p.pass_number
            """;

    private final JdbcClient jdbc;
    private final EventCatalog catalog;
    private final DateTimeFormatter csvTime;

    public AttendanceReportService(JdbcClient jdbc, EventCatalog catalog, CheckinProperties properties) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.csvTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(properties.timeZone());
    }

    public AttendanceSummary summary(long eventId) {
        EventInfo event = catalog.event(eventId);
        long valid = count("SELECT count(*) FROM pass_ref WHERE event_id = :event AND status = 'VALID'", eventId);
        long checkedIn = count("SELECT count(DISTINCT pass_id) FROM attendance WHERE event_id = :event", eventId);
        long inside = count("SELECT count(*) FROM presence WHERE event_id = :event AND state = 'IN'", eventId);

        Map<Long, Long> bySession = new HashMap<>();
        jdbc.sql("SELECT session_id, count(*) AS n FROM attendance WHERE event_id = :event GROUP BY session_id")
                .param("event", eventId)
                .query((rs, row) -> bySession.put(rs.getLong("session_id"), rs.getLong("n")))
                .list();
        List<SessionCount> sessions = new ArrayList<>();
        if (event.sessions().isEmpty()) {
            sessions.add(new SessionCount(
                    0, "Whole event", bySession.getOrDefault(0L, 0L), percent(bySession.getOrDefault(0L, 0L), valid)));
        } else {
            for (SessionInfo session : event.sessions().stream()
                    .sorted(java.util.Comparator.comparing(SessionInfo::startsAt))
                    .toList()) {
                long n = bySession.getOrDefault(session.id(), 0L);
                sessions.add(new SessionCount(session.id(), session.title(), n, percent(n, valid)));
            }
        }
        List<GateCount> gates = jdbc.sql("""
                        SELECT gate_code, count(*) AS n FROM attendance WHERE event_id = :event
                        GROUP BY gate_code ORDER BY gate_code
                        """)
                .param("event", eventId)
                .query((rs, row) -> new GateCount(rs.getString("gate_code"), rs.getLong("n")))
                .list();
        // max() of no rows is NULL: read it as a list, not single() (which refuses a null value).
        Instant lastScan = jdbc.sql(
                        "SELECT max(received_at) FROM scan_log WHERE event_id = :event AND mode <> 'LOOKUP'")
                .param("event", eventId)
                .query((rs, row) -> Optional.ofNullable(instant(rs.getTimestamp(1))))
                .single()
                .orElse(null);
        return new AttendanceSummary(
                eventId,
                event.code(),
                event.title(),
                valid,
                checkedIn,
                percent(checkedIn, valid),
                inside,
                sessions,
                gates,
                lastScan);
    }

    /** One row per pass, ordered by pass number; {@code presence} (IN, OUT, NOT_ARRIVED) filters. */
    public PageResponse<AttendeeRow> attendees(long eventId, String presence, int page, int size) {
        catalog.event(eventId); // 404 for an unknown event
        long total = jdbc.sql("SELECT count(*) FROM (" + ROWS + ") rows")
                .param("event", eventId)
                .param("presence", presence)
                .query(Long.class)
                .single();
        List<AttendeeRow> rows = jdbc.sql(ROWS + " LIMIT :size OFFSET :offset")
                .param("event", eventId)
                .param("presence", presence)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(AttendanceReportService::row)
                .list();
        int pages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(rows, page, size, total, pages);
    }

    /** Every pass of the event as CSV (UTF-8, header row, every field quoted). */
    public void exportCsv(long eventId, Writer out) throws IOException {
        EventInfo event = catalog.event(eventId);
        List<AttendeeRow> rows = jdbc.sql(ROWS)
                .param("event", eventId)
                .param("presence", null)
                .query(AttendanceReportService::row)
                .list();
        out.write(csv(
                "passNumber",
                "holderName",
                "holderEmail",
                "passStatus",
                "sessionsAttended",
                "sessionsTotal",
                "firstCheckIn",
                "lastGate",
                "presence"));
        for (AttendeeRow r : rows) {
            out.write(csv(
                    r.passNumber(),
                    r.holderName(),
                    r.holderEmail(),
                    r.passStatus(),
                    String.valueOf(r.sessionsAttended()),
                    String.valueOf(event.sessionsTotal()),
                    r.firstCheckIn() == null ? "" : csvTime.format(r.firstCheckIn()),
                    r.lastGate(),
                    r.presence()));
        }
    }

    /** Valid passes that attended at least the event's required sessions (all, unless the event says fewer). */
    public List<CertificateRow> certificates(long eventId) {
        EventInfo event = catalog.event(eventId);
        int required = event.sessionsRequired();
        return jdbc
                .sql(ROWS)
                .param("event", eventId)
                .param("presence", null)
                .query(AttendanceReportService::row)
                .list()
                .stream()
                .filter(r -> "VALID".equals(r.passStatus()) && r.sessionsAttended() >= required)
                .map(r -> new CertificateRow(
                        r.passNumber(), r.holderName(), r.holderEmail(), r.sessionsAttended(), required))
                .toList();
    }

    /** The caller's own attendance, one row per pass. Event details are best effort (null if event-service is down). */
    public List<MyAttendance> mine(UUID userId) {
        record Mine(long eventId, String passNumber, int sessions, Instant firstIn) {}
        List<Mine> rows = jdbc.sql("""
                        SELECT p.event_id, p.pass_number, count(a.id) AS sessions, min(a.checked_in_at) AS first_in
                        FROM pass_ref p LEFT JOIN attendance a ON a.pass_id = p.pass_id
                        WHERE p.user_id = :user AND p.pass_number IS NOT NULL
                        GROUP BY p.event_id, p.pass_number
                        ORDER BY min(a.checked_in_at) DESC NULLS LAST, p.pass_number
                        """)
                .param("user", userId)
                .query((rs, row) -> new Mine(
                        rs.getLong("event_id"),
                        rs.getString("pass_number"),
                        rs.getInt("sessions"),
                        instant(rs.getTimestamp("first_in"))))
                .list();
        List<MyAttendance> result = new ArrayList<>();
        for (Mine m : rows) {
            EventInfo event = null;
            try {
                event = catalog.event(m.eventId());
            } catch (RuntimeException ex) {
                // best effort: the row still shows the pass and its count
            }
            result.add(new MyAttendance(
                    m.eventId(),
                    event == null ? null : event.code(),
                    event == null ? null : event.title(),
                    m.passNumber(),
                    m.sessions(),
                    event == null ? null : event.sessionsTotal(),
                    event == null ? null : event.sessionsRequired(),
                    event == null ? null : m.sessions() >= event.sessionsRequired(),
                    m.firstIn()));
        }
        return result;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private long count(String sql, long eventId) {
        return jdbc.sql(sql).param("event", eventId).query(Long.class).single();
    }

    static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static AttendeeRow row(ResultSet rs, int row) throws SQLException {
        return new AttendeeRow(
                rs.getObject("pass_id", UUID.class),
                rs.getString("pass_number"),
                rs.getString("holder_name"),
                rs.getString("holder_email"),
                rs.getString("status"),
                rs.getInt("sessions"),
                instant(rs.getTimestamp("first_in")),
                rs.getString("last_gate"),
                rs.getString("presence"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * One CSV line: every field quoted, inner quotes doubled, and a leading {@code = + - @} (or tab/CR) prefixed with
     * {@code '} so a spreadsheet never runs a holder name as a formula (plan §18 gotcha 8).
     */
    static String csv(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            String value = fields[i] == null ? "" : fields[i];
            if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
                value = "'" + value;
            }
            line.append(i == 0 ? "" : ",")
                    .append('"')
                    .append(value.replace("\"", "\"\""))
                    .append('"');
        }
        return line.append("\r\n").toString();
    }
}

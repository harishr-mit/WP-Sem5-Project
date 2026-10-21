package com.mitpass.checkin.service;

import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.domain.ScanResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live dashboard's numbers (docs/plans/phase-7.md §4–5, D16). Counters live in Redis; PostgreSQL stays the source
 * of truth (D14). Per event: a set of the passes checked in and one of the passes inside (SADD/SREM are idempotent, so
 * a retried or raced update can't miscount them), a hash of per-gate and per-5-minute check-in counters, and the last
 * scans. They are updated after the scan's transaction has committed, and rebuilt from the database when they are
 * missing or older than {@code rebuild-every}, which also heals any drift. Redis being down never fails a scan: the
 * update is skipped and the snapshot is read from the database.
 */
@Service
public class LiveStats {

    private static final Logger log = LoggerFactory.getLogger(LiveStats.class);

    public record GateCount(String code, long checkedIn) {}

    public record Bucket(Instant start, long count) {}

    /** One ticker line: first name only, it may be on a projector (owner, Q7). */
    public record RecentScan(Instant at, String firstName, String gateCode, String code, String color) {}

    public record LiveSnapshot(
            long eventId,
            Instant at,
            long confirmed,
            long checkedIn,
            long inside,
            Integer capacity,
            List<GateCount> gates,
            List<Bucket> arrivals,
            List<RecentScan> recent) {}

    /** Published after every recorded scan; the STOMP feed pushes a snapshot for it. */
    public record LiveChanged(long eventId) {}

    private record Data(
            List<String> passes,
            List<String> inside,
            Map<String, Long> counts,
            List<RecentScan> recent,
            long confirmed) {}

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CheckinProperties.Live properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public LiveStats(
            StringRedisTemplate redis,
            JdbcClient jdbc,
            JsonMapper json,
            CheckinProperties properties,
            ApplicationEventPublisher events,
            Clock clock) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties.live();
        this.events = events;
        this.clock = clock;
    }

    /** After a scan (its transaction has committed). Never throws. */
    public void record(ScanOutcome outcome) {
        if (outcome.eventId() == null) {
            return;
        }
        long eventId = outcome.eventId();
        try {
            // a rebuild now reads the committed scan already (counters and ticker): don't add it twice
            boolean rebuilt = ensureFresh(eventId);
            String passId = outcome.attendee() == null
                    ? null
                    : outcome.attendee().passId().toString();
            if (outcome.result() == ScanResult.ACCEPTED && passId != null) {
                redis.opsForSet().add(key(eventId, "passes"), passId);
                redis.opsForSet().add(key(eventId, "inside"), passId);
                if (!outcome.reentry() && !rebuilt) {
                    redis.opsForHash().increment(key(eventId, "counts"), "gate:" + outcome.gateCode(), 1);
                    redis.opsForHash().increment(key(eventId, "counts"), bucketField(outcome.checkedInAt()), 1);
                }
            } else if (outcome.result() == ScanResult.CHECKED_OUT && passId != null) {
                redis.opsForSet().remove(key(eventId, "inside"), passId);
            }
            if (!rebuilt) {
                pushRecent(eventId, outcome);
            }
            for (String part : List.of("passes", "inside", "counts", "recent")) {
                redis.expire(key(eventId, part), properties.keyTtl());
            }
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("Live counters not updated for event {} (Redis): {}", eventId, e.getMessage());
        }
        events.publishEvent(new LiveChanged(eventId));
    }

    private void pushRecent(long eventId, ScanOutcome outcome) {
        RecentScan line = new RecentScan(
                clock.instant(),
                firstName(outcome.attendee() == null ? null : outcome.attendee().holderName()),
                outcome.gateCode(),
                outcome.result().name(),
                outcome.result().color().name());
        redis.opsForList().leftPush(key(eventId, "recent"), json.writeValueAsString(line));
        redis.opsForList().trim(key(eventId, "recent"), 0, properties.recentSize() - 1);
    }

    /** "Checked in at this gate" on the scanner; null when Redis is out of reach. */
    public Long gateCheckedIn(long eventId, String gateCode) {
        try {
            ensureFresh(eventId);
            Object value = redis.opsForHash().get(key(eventId, "counts"), "gate:" + gateCode);
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (DataAccessException | IllegalStateException e) {
            return null;
        }
    }

    public LiveSnapshot snapshot(long eventId, Integer capacity) {
        try {
            ensureFresh(eventId);
            Map<Object, Object> raw = redis.opsForHash().entries(key(eventId, "counts"));
            Map<String, Long> counts = new HashMap<>();
            raw.forEach((k, v) -> counts.put(k.toString(), Long.parseLong(v.toString())));
            List<RecentScan> recent = new ArrayList<>();
            for (String line : redis.opsForList().range(key(eventId, "recent"), 0, -1)) {
                recent.add(json.readValue(line, RecentScan.class));
            }
            Long passes = redis.opsForSet().size(key(eventId, "passes"));
            Long inside = redis.opsForSet().size(key(eventId, "inside"));
            return snapshot(
                    eventId,
                    capacity,
                    passes == null ? 0 : passes,
                    inside == null ? 0 : inside,
                    counts,
                    recent,
                    counts.getOrDefault("confirmed", 0L));
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("Live snapshot for event {} from the database (Redis): {}", eventId, e.getMessage());
            Data data = load(eventId);
            return snapshot(
                    eventId,
                    capacity,
                    data.passes().size(),
                    data.inside().size(),
                    data.counts(),
                    data.recent(),
                    data.confirmed());
        }
    }

    /** Throws away the event's counters, so the next read rebuilds them (tests; manual repair). */
    public void forget(long eventId) {
        redis.delete(List.of(
                key(eventId, "fresh"),
                key(eventId, "passes"),
                key(eventId, "inside"),
                key(eventId, "counts"),
                key(eventId, "recent")));
    }

    private LiveSnapshot snapshot(
            long eventId,
            Integer capacity,
            long checkedIn,
            long inside,
            Map<String, Long> counts,
            List<RecentScan> recent,
            long confirmed) {
        List<GateCount> gates = new ArrayList<>();
        List<Bucket> arrivals = new ArrayList<>();
        counts.forEach((field, n) -> {
            if (field.startsWith("gate:")) {
                gates.add(new GateCount(field.substring(5), n));
            } else if (field.startsWith("bucket:")) {
                arrivals.add(new Bucket(Instant.ofEpochSecond(Long.parseLong(field.substring(7))), n));
            }
        });
        gates.sort(Comparator.comparing(GateCount::code));
        arrivals.sort(Comparator.comparing(Bucket::start));
        return new LiveSnapshot(
                eventId, clock.instant(), confirmed, checkedIn, inside, capacity, gates, arrivals, List.copyOf(recent));
    }

    /** @return true if the counters were rebuilt from the database just now */
    private boolean ensureFresh(long eventId) {
        if (Boolean.TRUE.equals(redis.hasKey(key(eventId, "fresh")))) {
            return false;
        }
        Data data = load(eventId);
        redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public List<Object> execute(RedisOperations ops) {
                ops.multi();
                ops.delete(List.of(
                        key(eventId, "passes"),
                        key(eventId, "inside"),
                        key(eventId, "counts"),
                        key(eventId, "recent")));
                if (!data.passes().isEmpty()) {
                    ops.opsForSet().add(key(eventId, "passes"), data.passes().toArray());
                }
                if (!data.inside().isEmpty()) {
                    ops.opsForSet().add(key(eventId, "inside"), data.inside().toArray());
                }
                Map<String, String> counts = new HashMap<>();
                data.counts().forEach((k, v) -> counts.put(k, v.toString()));
                counts.put("confirmed", Long.toString(data.confirmed()));
                ops.opsForHash().putAll(key(eventId, "counts"), counts);
                for (RecentScan line : data.recent()) {
                    ops.opsForList().rightPush(key(eventId, "recent"), json.writeValueAsString(line));
                }
                for (String part : List.of("passes", "inside", "counts", "recent")) {
                    ops.expire(key(eventId, part), properties.keyTtl());
                }
                ops.opsForValue().set(key(eventId, "fresh"), "1", properties.rebuildEvery());
                return ops.exec();
            }
        });
        return true;
    }

    /** Everything the counters hold, from this service's own tables. */
    private Data load(long eventId) {
        List<String> passes = jdbc.sql("SELECT DISTINCT pass_id FROM attendance WHERE event_id = :event")
                .param("event", eventId)
                .query((rs, row) -> rs.getString(1))
                .list();
        List<String> inside = jdbc.sql("SELECT pass_id FROM presence WHERE event_id = :event AND state = 'IN'")
                .param("event", eventId)
                .query((rs, row) -> rs.getString(1))
                .list();
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("SELECT gate_code, count(*) FROM attendance WHERE event_id = :event GROUP BY gate_code")
                .param("event", eventId)
                .query((rs, row) -> counts.put("gate:" + rs.getString(1), rs.getLong(2)))
                .list();
        long width = properties.bucket().toSeconds();
        jdbc.sql("""
                        SELECT (floor(extract(epoch FROM checked_in_at) / :width) * :width)::bigint, count(*)
                        FROM attendance WHERE event_id = :event GROUP BY 1
                        """)
                .param("event", eventId)
                .param("width", width)
                .query((rs, row) -> counts.put("bucket:" + rs.getLong(1), rs.getLong(2)))
                .list();
        long confirmed = jdbc.sql("SELECT count(*) FROM pass_ref WHERE event_id = :event AND status = 'VALID'")
                .param("event", eventId)
                .query(Long.class)
                .single();
        List<RecentScan> recent = jdbc.sql("""
                        SELECT s.received_at, p.holder_name, s.gate_id, s.result,
                               -- scan_log keeps the gate id only; any check-in at that gate knows its code
                               (SELECT a.gate_code FROM attendance a WHERE a.gate_id = s.gate_id LIMIT 1) AS gate_code
                        FROM scan_log s LEFT JOIN pass_ref p ON p.pass_id = s.pass_id
                        WHERE s.event_id = :event AND s.mode <> 'LOOKUP'
                        ORDER BY s.received_at DESC LIMIT :size
                        """)
                .param("event", eventId)
                .param("size", properties.recentSize())
                .query((rs, row) -> {
                    ScanResult result = ScanResult.valueOf(rs.getString("result"));
                    Timestamp at = rs.getTimestamp("received_at");
                    return new RecentScan(
                            at.toInstant(),
                            firstName(rs.getString("holder_name")),
                            rs.getString("gate_code"),
                            result.name(),
                            result.color().name());
                })
                .list();
        return new Data(passes, inside, counts, recent, confirmed);
    }

    private String bucketField(Instant at) {
        long width = properties.bucket().toSeconds();
        Instant when = at == null ? clock.instant() : at;
        return "bucket:" + Math.floorDiv(when.getEpochSecond(), width) * width;
    }

    public static String firstName(String holderName) {
        if (holderName == null || holderName.isBlank()) {
            return null;
        }
        return holderName.strip().split("\\s+")[0];
    }

    private static String key(long eventId, String part) {
        return "mitpass:live:" + eventId + ":" + part;
    }
}

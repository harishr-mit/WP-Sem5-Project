package com.mitpass.checkin.service;

import com.mitpass.checkin.domain.Attendance;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.domain.Presence;
import com.mitpass.checkin.domain.PresenceChange;
import com.mitpass.checkin.domain.PresenceState;
import com.mitpass.checkin.domain.ScanLog;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.repository.AttendanceRepository;
import com.mitpass.checkin.repository.PresenceChangeRepository;
import com.mitpass.checkin.repository.PresenceRepository;
import com.mitpass.checkin.repository.ScanLogRepository;
import com.mitpass.checkin.verify.ScanContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The check-in itself (docs/plans/phase-5.md §4): one transaction for the presence move, the attendance row, its
 * history, the scan log and the {@code AttendanceMarked} outbox row. Race-safe twice over: the presence row is locked
 * first ({@code SELECT … FOR UPDATE}), so two scans of one pass are decided one after the other; and
 * {@code UNIQUE (pass_id, session_id)} on attendance holds even if that lock were missing.
 */
@Component
public class CheckInTransaction {

    private final PresenceRepository presences;
    private final AttendanceRepository attendances;
    private final PresenceChangeRepository history;
    private final ScanLogRepository logs;
    private final CheckinEvents events;
    private final ScanOutcomes outcomes;

    public CheckInTransaction(
            PresenceRepository presences,
            AttendanceRepository attendances,
            PresenceChangeRepository history,
            ScanLogRepository logs,
            CheckinEvents events,
            ScanOutcomes outcomes) {
        this.presences = presences;
        this.attendances = attendances;
        this.history = history;
        this.logs = logs;
        this.events = events;
        this.outcomes = outcomes;
    }

    @Transactional
    public ScanOutcome checkIn(ScanContext scan) {
        PassRef pass = scan.pass();
        Instant now = scan.now();
        Presence presence = lockPresence(pass, now);

        Result result = scan.command().mode() == ScanMode.OUT ? checkOut(scan, presence) : checkIn(scan, presence);

        ScanLog log = outcomes.log(scan, result.result(), result.result().name());
        logs.save(log);
        return outcomes.outcome(scan, result.result(), result.details(), result.firstCheckIn(), result.reentry());
    }

    /**
     * Creates the presence row if needed and locks it ({@code SELECT … FOR UPDATE}). A concurrent scan of the same pass
     * waits here until this transaction ends. Overridable only so a test can show the unique constraint alone holds.
     */
    protected Presence lockPresence(PassRef pass, Instant now) {
        presences.createIfMissing(pass.passId(), pass.eventId(), now);
        return presences.lockById(pass.passId()).orElseThrow();
    }

    private record Result(ScanResult result, Map<String, String> details, Instant firstCheckIn, boolean reentry) {}

    private Result checkIn(ScanContext scan, Presence presence) {
        PassRef pass = scan.pass();
        long session = scan.session().sessionId();
        Optional<Attendance> earlier = attendances.findByPassIdAndSessionId(pass.passId(), session);
        if (earlier.isPresent()) {
            Attendance first = earlier.get();
            if (presence.getState() == PresenceState.IN) {
                return new Result(
                        ScanResult.ALREADY_CHECKED_IN,
                        Map.of("time", outcomes.time(first.getCheckedInAt()), "gate", first.getGateCode()),
                        first.getCheckedInAt(),
                        false);
            }
            if (!scan.event().allowsReentry()) {
                return new Result(ScanResult.REENTRY_NOT_ALLOWED, Map.of(), first.getCheckedInAt(), false);
            }
            move(scan, presence, PresenceState.IN, session);
            return new Result(ScanResult.ACCEPTED, Map.of(), first.getCheckedInAt(), true);
        }

        Attendance attendance = attendances.saveAndFlush(new Attendance(
                pass.passId(),
                pass.eventId(),
                session,
                scan.gate().id(),
                scan.gate().code(),
                scan.volunteer().id(),
                scan.method(),
                scan.now(),
                scannedAt(scan)));
        if (presence.getState() == PresenceState.IN) {
            presence.enterSession(session, scan.now()); // still inside from the previous session
        } else {
            move(scan, presence, PresenceState.IN, session);
        }
        events.attendanceMarked(attendance, pass, !scan.event().allowsReentry());
        return new Result(ScanResult.ACCEPTED, Map.of(), attendance.getCheckedInAt(), false);
    }

    private Result checkOut(ScanContext scan, Presence presence) {
        if (presence.getState() != PresenceState.IN) {
            return new Result(ScanResult.NOT_CHECKED_IN, Map.of(), null, false);
        }
        move(scan, presence, PresenceState.OUT, presence.getSessionId());
        return new Result(ScanResult.CHECKED_OUT, Map.of(), null, false);
    }

    private void move(ScanContext scan, Presence presence, PresenceState target, long session) {
        PresenceState from = presence.moveTo(target, session, scan.now());
        history.save(new PresenceChange(
                presence.getPassId(),
                from,
                target,
                session,
                scan.gate().id(),
                scan.scanId(),
                scan.volunteer().id().toString(),
                scan.now()));
    }

    static Instant scannedAt(ScanContext scan) {
        return scan.command().scannedAt() == null ? scan.now() : scan.command().scannedAt();
    }
}

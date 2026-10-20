package com.mitpass.checkin.service;

import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.domain.Presence;
import com.mitpass.checkin.domain.PresenceState;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.repository.PresenceRepository;
import com.mitpass.checkin.verify.ScanContext;
import com.mitpass.checkin.verify.ScanContext.ScanCommand;
import com.mitpass.checkin.verify.ScanPipeline;
import com.mitpass.checkin.verify.ScanStep.Rejection;
import com.mitpass.common.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * A scan from start to finish (docs/plans/phase-5.md §4): the verification pipeline, then the check-in transaction.
 * Never throws for a refused scan: every outcome is a {@link ScanOutcome} and every scan leaves a scan log row.
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    /** What a successful lookup shows the volunteer for the visual check (manual entry, §7). */
    public record Lookup(ScanOutcome outcome, String passStatus, PresenceState presence) {}

    private final ScanPipeline pipeline;
    private final CheckInTransaction checkIn;
    private final ScanLogWriter logWriter;
    private final ScanOutcomes outcomes;
    private final PresenceRepository presences;
    private final LiveStats live;
    private final OfflineScanLog offlineLog;
    private final CheckinProperties properties;
    private final Clock clock;

    public ScanService(
            ScanPipeline pipeline,
            CheckInTransaction checkIn,
            ScanLogWriter logWriter,
            ScanOutcomes outcomes,
            PresenceRepository presences,
            LiveStats live,
            OfflineScanLog offlineLog,
            CheckinProperties properties,
            Clock clock) {
        this.pipeline = pipeline;
        this.checkIn = checkIn;
        this.logWriter = logWriter;
        this.outcomes = outcomes;
        this.presences = presences;
        this.live = live;
        this.offlineLog = offlineLog;
        this.properties = properties;
        this.clock = clock;
    }

    /** One queued offline scan as the phone sends it; {@code localId} is the phone's own id for it. */
    public record OfflineScan(String localId, String token, String passNumber, long gateId, Instant scannedAt) {}

    /** Every outcome also updates the live dashboard (after the check-in transaction has committed; P7). */
    public ScanOutcome scan(CurrentUser volunteer, ScanCommand command) {
        ScanOutcome outcome = verifyAndCheckIn(new ScanContext(volunteer, command, clock.instant()));
        live.record(outcome);
        return outcome;
    }

    /**
     * Scans a phone queued while offline (docs/plans/phase-7.md §8, owner Q2/Q12), each through the full pipeline with
     * its {@code scannedAt} as the clock, oldest first. The check-in already on record wins: a later one is
     * {@code DUPLICATE_OFFLINE}, and no attendance row is ever rewritten. A resent scan (same device and local id)
     * gets its first answer again. Answers come back in the order sent.
     */
    public List<ScanOutcome> batch(CurrentUser volunteer, String deviceId, List<OfflineScan> scans) {
        Instant received = clock.instant();
        Map<String, ScanOutcome> answers = new HashMap<>();
        for (OfflineScan item : scans.stream()
                .sorted(Comparator.comparing(OfflineScan::scannedAt))
                .toList()) {
            answers.computeIfAbsent(item.localId(), id -> offline(volunteer, deviceId, item, received));
        }
        return scans.stream().map(item -> answers.get(item.localId())).toList();
    }

    private ScanOutcome offline(CurrentUser volunteer, String deviceId, OfflineScan item, Instant received) {
        Optional<ScanResult> earlier = offlineLog.result(deviceId, item.localId());
        if (earlier.isPresent()) {
            return outcomes.replayed(earlier.get());
        }
        ScanContext scan = new ScanContext(
                volunteer,
                new ScanCommand(
                        item.token(), item.passNumber(), item.gateId(), deviceId, item.scannedAt(), ScanMode.IN),
                item.scannedAt(),
                true);
        ScanOutcome outcome;
        if (item.scannedAt().isBefore(received.minus(properties.offline().maxAge()))
                || item.scannedAt().isAfter(received.plus(properties.offline().maxFuture()))) {
            outcome = reject(
                    scan, new Rejection(ScanResult.REJECTED_OFFLINE_CLOCK, Map.of(), "scanned at " + item.scannedAt()));
        } else {
            outcome = verifyAndCheckIn(scan);
            if (outcome.result() == ScanResult.ALREADY_CHECKED_IN) {
                outcome = outcomes.as(outcome, ScanResult.DUPLICATE_OFFLINE);
            }
        }
        offlineLog.mark(outcome.scanId(), item.localId(), outcome.result(), received);
        live.record(outcome);
        return outcome;
    }

    private ScanOutcome verifyAndCheckIn(ScanContext scan) {
        Optional<Rejection> rejection = pipeline.run(scan);
        if (rejection.isPresent()) {
            return reject(scan, rejection.get());
        }
        try {
            return checkIn.checkIn(scan);
        } catch (DataIntegrityViolationException ex) {
            // The unique (pass, session) constraint caught a duplicate the lock didn't (should not happen; tested).
            log.warn(
                    "Duplicate check-in caught by the unique constraint for pass {}",
                    scan.pass().passId());
            return reject(scan, new Rejection(ScanResult.ALREADY_CHECKED_IN, Map.of(), "unique constraint"));
        }
    }

    /**
     * Manual entry, step one: the same pipeline for a typed number, without checking in. A refusal is final; otherwise
     * the volunteer compares the name with the person and confirms with a scan of the same number.
     */
    public Lookup lookup(CurrentUser volunteer, String passNumber, long gateId, String deviceId) {
        ScanContext scan = new ScanContext(
                volunteer, new ScanCommand(null, passNumber, gateId, deviceId, null, ScanMode.LOOKUP), clock.instant());
        Optional<Rejection> rejection = pipeline.run(scan);
        if (rejection.isPresent()) {
            return new Lookup(reject(scan, rejection.get()), null, null);
        }
        logWriter.write(outcomes.log(scan, ScanResult.ACCEPTED, "lookup"));
        PresenceState presence =
                presences.findById(scan.pass().passId()).map(Presence::getState).orElse(PresenceState.NOT_ARRIVED);
        return new Lookup(
                outcomes.outcome(scan, ScanResult.ACCEPTED, Map.of(), null, false),
                scan.pass().status(),
                presence);
    }

    private ScanOutcome reject(ScanContext scan, Rejection rejection) {
        logWriter.write(outcomes.log(scan, rejection.result(), rejection.note()));
        return outcomes.outcome(scan, rejection.result(), rejection.details(), null, false);
    }
}

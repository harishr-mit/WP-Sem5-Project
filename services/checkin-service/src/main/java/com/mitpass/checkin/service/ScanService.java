package com.mitpass.checkin.service;

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
    private final Clock clock;

    public ScanService(
            ScanPipeline pipeline,
            CheckInTransaction checkIn,
            ScanLogWriter logWriter,
            ScanOutcomes outcomes,
            PresenceRepository presences,
            Clock clock) {
        this.pipeline = pipeline;
        this.checkIn = checkIn;
        this.logWriter = logWriter;
        this.outcomes = outcomes;
        this.presences = presences;
        this.clock = clock;
    }

    public ScanOutcome scan(CurrentUser volunteer, ScanCommand command) {
        ScanContext scan = new ScanContext(volunteer, command, clock.instant());
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

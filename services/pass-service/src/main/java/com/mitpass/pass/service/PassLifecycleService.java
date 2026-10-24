package com.mitpass.pass.service;

import com.mitpass.common.messaging.contract.AttendanceMarked;
import com.mitpass.common.messaging.contract.AttendanceVoided;
import com.mitpass.common.messaging.contract.EventRescheduled;
import com.mitpass.common.messaging.contract.EventStateChanged;
import com.mitpass.common.messaging.contract.RegistrationCancelled;
import com.mitpass.common.messaging.contract.RegistrationConfirmed;
import com.mitpass.common.pass.PassNumber;
import com.mitpass.common.web.error.BusinessRuleException;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.pass.domain.EventRef;
import com.mitpass.pass.domain.Pass;
import com.mitpass.pass.domain.PassChange;
import com.mitpass.pass.domain.PassStatus;
import com.mitpass.pass.domain.Revocation;
import com.mitpass.pass.domain.RevokeReason;
import com.mitpass.pass.domain.TransitionTrigger;
import com.mitpass.pass.repository.EventRefRepository;
import com.mitpass.pass.repository.EventRefStore;
import com.mitpass.pass.repository.PassChangeRepository;
import com.mitpass.pass.repository.PassRepository;
import com.mitpass.pass.repository.RevocationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every pass state change (docs/plans/phase-4.md §5, §8). Each method runs in the caller's transaction or its own: for
 * a consumer that is the {@code IdempotentConsumer} transaction, so the processed-message row, the pass, its history,
 * revocation entries and outbox rows commit together.
 */
@Service
@Transactional
public class PassLifecycleService {

    static final String SYSTEM = "system";
    private static final Logger log = LoggerFactory.getLogger(PassLifecycleService.class);
    private static final EnumSet<PassStatus> VALID = EnumSet.of(PassStatus.ISSUED, PassStatus.ACTIVE);

    private final PassRepository passes;
    private final PassChangeRepository history;
    private final RevocationRepository revocations;
    private final EventRefRepository eventRefs;
    private final EventRefStore eventStore;
    private final PassEvents events;
    private final PassPolicy policy;
    private final Clock clock;

    public PassLifecycleService(
            PassRepository passes,
            PassChangeRepository history,
            RevocationRepository revocations,
            EventRefRepository eventRefs,
            EventRefStore eventStore,
            PassEvents events,
            PassPolicy policy,
            Clock clock) {
        this.passes = passes;
        this.history = history;
        this.revocations = revocations;
        this.eventRefs = eventRefs;
        this.eventStore = eventStore;
        this.events = events;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * {@code RegistrationConfirmed} → a new ISSUED pass (ACTIVE at once if its live code has already unlocked), plus
     * {@code PassIssued}. Skipped when the registration already has a pass, was cancelled meanwhile, or the event is
     * cancelled or over.
     *
     * @return the new pass, or empty if none was issued
     */
    public Optional<Pass> issue(RegistrationConfirmed confirmed, Instant occurredAt) {
        Instant now = clock.instant();
        eventStore.upsertSchedule(
                confirmed.eventId(),
                confirmed.eventCode(),
                confirmed.eventTitle(),
                confirmed.eventStartsAt(),
                confirmed.eventEndsAt(),
                occurredAt,
                now);
        if (passes.existsByRegistrationId(confirmed.registrationId())) {
            log.info("Registration {} already has a pass", confirmed.registrationId());
            return Optional.empty();
        }
        if (eventStore.isCancelled(confirmed.registrationId())) {
            log.info("Registration {} was cancelled before its pass was issued", confirmed.registrationId());
            return Optional.empty();
        }
        EventRef event = eventRefs.findById(confirmed.eventId()).orElseThrow();
        if ("CANCELLED".equals(event.getStatus()) || "COMPLETED".equals(event.getStatus())) {
            log.info(
                    "Event {} is {}: no pass for registration {}",
                    event.getId(),
                    event.getStatus(),
                    confirmed.registrationId());
            return Optional.empty();
        }
        Pass pass = newPass(
                event,
                confirmed.registrationId(),
                confirmed.userId(),
                confirmed.userName(),
                confirmed.userEmail(),
                now,
                SYSTEM,
                TransitionTrigger.SYSTEM,
                confirmed.viaPromotion() ? "Promoted from the waitlist" : "Registration confirmed");
        return Optional.of(pass);
    }

    /** A new ISSUED pass with the next number (ACTIVE at once if its live code has unlocked), plus PassIssued. */
    private Pass newPass(
            EventRef event,
            UUID registrationId,
            UUID userId,
            String holderName,
            String holderEmail,
            Instant now,
            String actor,
            TransitionTrigger trigger,
            String reason) {
        int seq = eventStore.nextPassSeq(event.getId());
        Pass pass = passes.save(new Pass(
                UUID.randomUUID(),
                PassNumber.of(event.getCode(), seq),
                registrationId,
                event,
                userId,
                holderName,
                holderEmail,
                now));
        record(pass, null, PassStatus.ISSUED, now, actor, trigger, reason);
        if (policy.isUnlocked(event, now) && !policy.isOver(event, now)) {
            move(pass, PassStatus.ACTIVE, now, SYSTEM, TransitionTrigger.SYSTEM, "Live code already unlocked");
        }
        events.issued(pass);
        log.info("Issued pass {} ({}) for registration {}", pass.getPassNumber(), pass.getStatus(), registrationId);
        return pass;
    }

    /**
     * RL10: an admin brought the event back (un-cancel, or reopen after it ended). Each registration whose current pass
     * was revoked by the cancellation, or expired with the event, gets a new pass; registrations cancelled meanwhile
     * don't. Safe to repeat: a registration that already has a valid pass is skipped.
     */
    private void reissueAfter(long eventId, boolean uncancelled, Instant now) {
        EventRef event = eventRefs.findById(eventId).orElseThrow();
        Map<UUID, Pass> current = new HashMap<>();
        for (Pass pass : passes.findByEvent_Id(eventId, org.springframework.data.domain.Pageable.unpaged())) {
            current.merge(pass.getRegistrationId(), pass, (a, b) -> currentOf(a, b));
        }
        int issued = 0;
        for (Pass pass : current.values()) {
            boolean due = uncancelled
                    ? pass.getStatus() == PassStatus.REVOKED && pass.getRevokeReason() == RevokeReason.EVENT_CANCELLED
                    : pass.getStatus() == PassStatus.EXPIRED;
            if (due && !eventStore.isCancelled(pass.getRegistrationId())) {
                newPass(
                        event,
                        pass.getRegistrationId(),
                        pass.getUserId(),
                        pass.getHolderName(),
                        pass.getHolderEmail(),
                        now,
                        SYSTEM,
                        TransitionTrigger.SYSTEM,
                        "Re-issued: event " + (uncancelled ? "reinstated" : "reopened") + " (replaces "
                                + pass.getPassNumber() + ")");
                issued++;
            }
        }
        log.info("Event {} {}: re-issued {} pass(es)", eventId, uncancelled ? "reinstated" : "reopened", issued);
    }

    private static Pass currentOf(Pass a, Pass b) {
        if (a.getStatus().isValid() != b.getStatus().isValid()) {
            return a.getStatus().isValid() ? a : b;
        }
        return a.getIssuedAt().isAfter(b.getIssuedAt()) ? a : b;
    }

    /**
     * An admin re-issues one revoked pass (RL10): a new pass for the same registration, unless the registration was
     * cancelled, it already has a valid pass, or the event is over or cancelled.
     */
    public Pass reissueByAdmin(UUID passId, String actor) {
        Pass old = passes.findDetailedById(passId).orElseThrow(() -> NotFoundException.of("Pass", passId));
        if (old.getStatus() != PassStatus.REVOKED) {
            throw new ConflictException(
                    "Only a revoked pass can be re-issued (this one is %s)".formatted(old.getStatus()));
        }
        if (passes.findByRegistrationId(old.getRegistrationId())
                .map(p -> p.getStatus().isValid())
                .orElse(false)) {
            throw new ConflictException("This registration already has a valid pass");
        }
        if (eventStore.isCancelled(old.getRegistrationId())) {
            throw new BusinessRuleException("The registration was cancelled, so it gets no pass");
        }
        EventRef event = old.getEvent();
        if ("CANCELLED".equals(event.getStatus()) || "COMPLETED".equals(event.getStatus())) {
            throw new BusinessRuleException("The event is %s: bring it back first".formatted(event.getStatus()));
        }
        return newPass(
                event,
                old.getRegistrationId(),
                old.getUserId(),
                old.getHolderName(),
                old.getHolderEmail(),
                clock.instant(),
                actor,
                TransitionTrigger.MANUAL,
                "Re-issued by an admin (replaces " + old.getPassNumber() + ")");
    }

    /** {@code RegistrationCancelled}: a CONFIRMED registration's pass is revoked; none yet → tombstone. */
    public void registrationCancelled(RegistrationCancelled cancelled) {
        if (!"CONFIRMED".equals(cancelled.previousStatus())) {
            return; // a waitlisted registration never had a pass
        }
        // Always remembered: a pass revoked by an event cancellation must not come back for it (RL10 re-issue).
        eventStore.markCancelled(cancelled.registrationId(), cancelled.cancelledAt());
        Optional<Pass> pass = passes.findByRegistrationId(cancelled.registrationId());
        if (pass.isEmpty()) {
            return;
        }
        if (pass.get().getStatus().isValid()) {
            revoke(pass.get(), SYSTEM, TransitionTrigger.SYSTEM, RevokeReason.REGISTRATION_CANCELLED, null);
        }
    }

    /**
     * {@code EventStateChanged}: records the status (if newer), then CANCELLED revokes every valid pass (saga step),
     * COMPLETED expires them, LIVE activates any still ISSUED.
     */
    public void eventStateChanged(EventStateChanged changed) {
        Instant now = clock.instant();
        // A state change can be the first thing pass-service hears about an event.
        eventStore.upsertSchedule(
                changed.eventId(),
                changed.eventCode(),
                null,
                changed.startsAt(),
                changed.endsAt(),
                changed.changedAt(),
                now);
        if (!eventStore.updateStatus(changed.eventId(), changed.toStatus(), changed.changedAt(), now)) {
            log.info("Ignoring an older state change of event {} ({})", changed.eventId(), changed.toStatus());
            return;
        }
        if ("CANCELLED".equals(changed.fromStatus())) {
            reissueAfter(changed.eventId(), true, now);
        } else if ("COMPLETED".equals(changed.fromStatus()) && "LIVE".equals(changed.toStatus())) {
            reissueAfter(changed.eventId(), false, now);
        }
        switch (changed.toStatus()) {
            case "CANCELLED" -> {
                List<Pass> valid = passes.findByEvent_IdAndStatusIn(changed.eventId(), VALID);
                valid.forEach(pass -> revoke(
                        pass, SYSTEM, TransitionTrigger.SYSTEM, RevokeReason.EVENT_CANCELLED, "Event cancelled"));
                log.info("Event {} cancelled: revoked {} pass(es)", changed.eventId(), valid.size());
            }
            case "COMPLETED" ->
                passes.findByEvent_IdAndStatusIn(changed.eventId(), VALID)
                        .forEach(pass -> move(
                                pass, PassStatus.EXPIRED, now, SYSTEM, TransitionTrigger.SYSTEM, "Event completed"));
            case "LIVE" ->
                passes.findByEvent_IdAndStatusIn(changed.eventId(), EnumSet.of(PassStatus.ISSUED))
                        .forEach(pass ->
                                move(pass, PassStatus.ACTIVE, now, SYSTEM, TransitionTrigger.SYSTEM, "Event is live"));
            default -> {
                // other moves only change the stored status
            }
        }
    }

    /** {@code EventRescheduled}: new start/end times (if newer); the scheduler follows them. */
    public void eventRescheduled(EventRescheduled rescheduled) {
        eventStore.upsertSchedule(
                rescheduled.eventId(),
                rescheduled.eventCode(),
                null,
                rescheduled.startsAt(),
                rescheduled.endsAt(),
                rescheduled.changedAt(),
                clock.instant());
    }

    /** {@code AttendanceMarked} (P5): the first check-in, and "used" for single-entry events (owner Q2). */
    public void attendanceMarked(AttendanceMarked marked) {
        passes.findById(marked.passId())
                .ifPresentOrElse(
                        pass -> pass.recordCheckIn(marked.checkedInAt(), marked.singleEntry()),
                        () -> log.warn("AttendanceMarked for unknown pass {}; ignored", marked.passId()));
    }

    /** {@code AttendanceVoided} (RL12): with no check-in left, the pass counts as never used again. */
    public void attendanceVoided(AttendanceVoided voided) {
        if (voided.stillCheckedIn()) {
            return;
        }
        passes.findById(voided.passId())
                .ifPresentOrElse(
                        Pass::clearCheckIn,
                        () -> log.warn("AttendanceVoided for unknown pass {}; ignored", voided.passId()));
    }

    /** An organizer or admin revokes a pass (409 {@code illegal-transition} if it is no longer valid). */
    public Pass revokeByStaff(UUID passId, String actor, boolean admin, String note) {
        Pass pass = passes.findDetailedById(passId).orElseThrow(() -> NotFoundException.of("Pass", passId));
        return revoke(pass, actor, TransitionTrigger.MANUAL, admin ? RevokeReason.ADMIN : RevokeReason.ORGANIZER, note);
    }

    /**
     * ISSUED → ACTIVE if the live code has unlocked and the scheduler hasn't caught up yet (called when the holder asks
     * for the live code), so the code appears on time.
     */
    public Pass activateIfDue(UUID passId) {
        Pass pass = passes.findDetailedById(passId).orElseThrow(() -> NotFoundException.of("Pass", passId));
        Instant now = clock.instant();
        if (pass.getStatus() == PassStatus.ISSUED
                && policy.isUnlocked(pass.getEvent(), now)
                && !policy.isOver(pass.getEvent(), now)) {
            move(pass, PassStatus.ACTIVE, now, SYSTEM, TransitionTrigger.SYSTEM, "Live code unlocked");
        }
        return pass;
    }

    /** Scheduler step: one pass, if it is still in the expected state. */
    public void activateOrExpire(UUID passId, PassStatus target) {
        Pass pass = passes.findDetailedById(passId).orElse(null);
        if (pass == null || !pass.getStatus().canMoveTo(target)) {
            return; // changed meanwhile (revoked, expired by a message)
        }
        String reason = target == PassStatus.ACTIVE ? "Live code unlocked" : "Event over";
        move(pass, target, clock.instant(), SYSTEM, TransitionTrigger.SCHEDULER, reason);
    }

    private Pass revoke(Pass pass, String actor, TransitionTrigger trigger, RevokeReason reason, String note) {
        Instant now = clock.instant();
        PassStatus from = pass.revoke(now, actor, reason, note);
        record(pass, from, PassStatus.REVOKED, now, actor, trigger, reason.name() + (note != null ? ": " + note : ""));
        revocations.save(new Revocation(pass.getId(), pass.getEvent().getId(), now, reason));
        events.revoked(pass);
        return passes.saveAndFlush(pass);
    }

    private void move(
            Pass pass, PassStatus target, Instant at, String actor, TransitionTrigger trigger, String reason) {
        PassStatus from = pass.moveTo(target, at);
        record(pass, from, target, at, actor, trigger, reason);
        passes.saveAndFlush(pass);
    }

    private void record(
            Pass pass,
            PassStatus from,
            PassStatus to,
            Instant at,
            String actor,
            TransitionTrigger trigger,
            String reason) {
        String text = reason == null || reason.length() <= 200 ? reason : reason.substring(0, 200);
        history.save(new PassChange(pass, from, to, at, actor, trigger, text));
    }
}

package com.mitpass.checkin.verify;

import com.mitpass.checkin.client.EventCatalog;
import com.mitpass.checkin.client.EventCatalog.Assignment;
import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.client.EventCatalog.GateInfo;
import com.mitpass.checkin.client.PassKeys;
import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.domain.SessionResolver;
import com.mitpass.checkin.domain.SessionResolver.Resolution;
import com.mitpass.checkin.repository.EventRefStore;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.checkin.service.PassCopyReconciler;
import com.mitpass.common.pass.PassNumber;
import com.mitpass.common.pass.PassTokenVerifier;
import com.mitpass.common.security.Roles;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The verification steps, in pipeline order (docs/plans/phase-5.md §4; owner Q4: the scanner check first, so an
 * unassigned account learns nothing about a pass). Each is a bean; {@link ScanPipeline} runs them in {@code @Order}.
 */
public final class Steps {

    private Steps() {}

    /**
     * 10. Is this volunteer assigned to the gate's event, and to this gate (or any gate)? Or does this organizer run the
     * gate's event (RL11)?
     */
    @Component
    @Order(10)
    public static class ScannerAssigned implements ScanStep {

        private final EventCatalog catalog;

        public ScannerAssigned(EventCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            long gateId = scan.command().gateId();
            List<Assignment> assignments = catalog.assignmentsOf(scan.volunteer());
            for (Assignment a : assignments) {
                if (a.gate() != null && a.gate().id() == gateId) {
                    return admit(scan, a.eventId(), a.gate());
                }
            }
            for (Assignment a : assignments) {
                if (a.gate() == null) {
                    Optional<GateInfo> gate = gateOf(a.eventId(), gateId);
                    if (gate.isPresent()) {
                        return admit(scan, a.eventId(), gate.get());
                    }
                }
            }
            // RL11 (owner, 2026-10-16): an organizer may scan at any gate of an event they organize, unassigned.
            if (scan.volunteer().hasRole(Roles.ORGANIZER)) {
                for (long eventId : catalog.eventsOrganizedBy(scan.volunteer().id())) {
                    Optional<GateInfo> gate = gateOf(eventId, gateId);
                    if (gate.isPresent()) {
                        return admit(scan, eventId, gate.get());
                    }
                }
            }
            // Elsewhere the volunteer rules apply, to organizers too.
            for (Assignment a : assignments) {
                if (a.gate() != null && gateOf(a.eventId(), gateId).isPresent()) {
                    return Rejection.of(
                            ScanResult.WRONG_GATE,
                            Map.of("gate", a.gate().name()),
                            "assigned to gate " + a.gate().code());
                }
            }
            return Rejection.of(ScanResult.UNAUTHORIZED_SCANNER, "no assignment for gate " + gateId);
        }

        private Optional<GateInfo> gateOf(long eventId, long gateId) {
            return catalog.gates(eventId).stream().filter(g -> g.id() == gateId).findFirst();
        }

        private Optional<Rejection> admit(ScanContext scan, long eventId, GateInfo gate) {
            scan.gate = gate;
            scan.event = catalog.event(eventId);
            return Optional.empty();
        }
    }

    /** 20. A QR token: genuine ES256 signature from a published pass key (D12). */
    @Component
    @Order(20)
    public static class Signature implements ScanStep {

        private final PassTokenVerifier verifier;
        private final PassKeys keys;

        public Signature(PassTokenVerifier verifier, PassKeys keys) {
            this.verifier = verifier;
            this.keys = keys;
        }

        @Override
        public boolean appliesTo(ScanContext scan) {
            return !scan.command().manual();
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            PassTokenVerifier.Result result = verifier.verify(scan.command().token(), keys::key);
            if (!result.verified()) {
                return Rejection.of(
                        ScanResult.INVALID_SIGNATURE, result.failure().name());
            }
            scan.token = result.token();
            return Optional.empty();
        }
    }

    /** 21. A typed pass number: format and check digit (Luhn, D12), and the event code part matches the gate's. */
    @Component
    @Order(21)
    public static class TypedNumber implements ScanStep {

        @Override
        public boolean appliesTo(ScanContext scan) {
            return scan.command().manual();
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            Optional<String> number = PassNumber.normalize(scan.command().passNumber());
            if (number.isEmpty()) {
                return Rejection.of(ScanResult.INVALID_PASS_NUMBER, "check digit or format");
            }
            scan.passNumber = number.get();
            String code = PassNumber.eventCode(number.get());
            if (!code.equalsIgnoreCase(scan.event().code())) {
                return Rejection.of(ScanResult.WRONG_EVENT, Map.of("event", code), "number of event " + code);
            }
            return Optional.empty();
        }
    }

    /** 30. Not expired, with a small clock-skew allowance for phones. */
    @Component
    @Order(30)
    public static class Expiry implements ScanStep {

        private final CheckinProperties properties;

        public Expiry(CheckinProperties properties) {
            this.properties = properties;
        }

        @Override
        public boolean appliesTo(ScanContext scan) {
            return !scan.command().manual();
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            Instant deadline = scan.token().expiresAt().plus(properties.tokenClockSkew());
            return scan.now().isAfter(deadline)
                    ? Rejection.of(
                            ScanResult.EXPIRED_TOKEN, "expired " + scan.token().expiresAt())
                    : Optional.empty();
        }
    }

    /** 40. The token is for the event this gate belongs to. */
    @Component
    @Order(40)
    public static class EventMatch implements ScanStep {

        private final EventCatalog catalog;

        public EventMatch(EventCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public boolean appliesTo(ScanContext scan) {
            return !scan.command().manual();
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            long eid = scan.token().eventId();
            if (eid == scan.event().id()) {
                return Optional.empty();
            }
            String other;
            try {
                other = catalog.event(eid).title();
            } catch (RuntimeException ex) { // unknown or unreachable: the answer is the same
                other = "another event";
            }
            return Rejection.of(ScanResult.WRONG_EVENT, Map.of("event", other), "token for event " + eid);
        }
    }

    /** 50. The event is LIVE, per the status copy (seeded from the owner if this service hasn't seen the event). */
    @Component
    @Order(50)
    public static class EventLive implements ScanStep {

        private final EventRefStore events;
        private final CheckinProperties properties;

        public EventLive(EventRefStore events, CheckinProperties properties) {
            this.events = events;
            this.properties = properties;
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            EventInfo event = scan.event();
            String status = events.status(event.id()).orElseGet(() -> {
                events.apply(event.id(), event.code(), event.status(), scan.now(), scan.now());
                return event.status();
            });
            return switch (status == null ? "" : status) {
                case "LIVE" -> Optional.empty();
                case "COMPLETED", "CANCELLED" -> Rejection.of(ScanResult.EVENT_CLOSED, "event " + status);
                default ->
                    Rejection.of(
                            ScanResult.NOT_YET_OPEN,
                            Map.of(
                                    "time",
                                    time(event.startsAt().minus(properties.doorsOpenBeforeSession()), properties)),
                            "event " + status);
            };
        }
    }

    /** 60. Which session the scan counts for; its doors are open. */
    @Component
    @Order(60)
    public static class SessionOpen implements ScanStep {

        private final CheckinProperties properties;

        public SessionOpen(CheckinProperties properties) {
            this.properties = properties;
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            EventInfo event = scan.event();
            Resolution session = SessionResolver.resolve(
                    event.sessions(),
                    event.startsAt(),
                    event.endsAt(),
                    scan.now(),
                    properties.doorsOpenBeforeSession());
            scan.session = session;
            return switch (session.state()) {
                case OPEN -> Optional.empty();
                case NOT_YET_OPEN ->
                    Rejection.of(
                            ScanResult.NOT_YET_OPEN,
                            Map.of("time", time(session.opensAt(), properties)),
                            "between sessions");
                case CLOSED -> Rejection.of(ScanResult.EVENT_CLOSED, "after the last session");
            };
        }
    }

    /**
     * 70. The pass is in the copy. If not, ask the owner first (the reconciler, with this service's identity): a single
     * fetch for a token's pass id, a rate-limited event sync for a typed number. Only then {@code UNKNOWN_PASS}.
     */
    @Component
    @Order(70)
    public static class PassKnown implements ScanStep {

        private final PassCopyStore passes;
        private final PassCopyReconciler reconciler;

        public PassKnown(PassCopyStore passes, PassCopyReconciler reconciler) {
            this.passes = passes;
            this.reconciler = reconciler;
        }

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            Optional<PassRef> pass = find(scan);
            if (pass.isEmpty() || !(pass.get().complete() || pass.get().revoked())) {
                if (scan.command().manual()) {
                    reconciler.syncEventSoon(scan.event().id());
                } else {
                    reconciler.fetchPass(scan.token().passId());
                }
                pass = find(scan);
            }
            if (pass.isEmpty() || !(pass.get().complete() || pass.get().revoked())) {
                return Rejection.of(ScanResult.UNKNOWN_PASS, "not in the copy, owner fetch found nothing");
            }
            if (pass.get().eventId() != scan.event().id()) {
                return Rejection.of(
                        ScanResult.WRONG_EVENT, Map.of("event", "another event"), "copy says another event");
            }
            scan.pass = pass.get();
            return Optional.empty();
        }

        private Optional<PassRef> find(ScanContext scan) {
            return scan.command().manual()
                    ? passes.findByNumber(scan.passNumber)
                    : passes.find(scan.token().passId());
        }
    }

    /** 80. Not revoked (the copy follows PassRevoked and the revocation feed, D14). */
    @Component
    @Order(80)
    public static class NotRevoked implements ScanStep {

        @Override
        public Optional<Rejection> check(ScanContext scan) {
            return scan.pass().revoked()
                    ? Rejection.of(ScanResult.REVOKED, "revoked " + scan.pass().revokeReason())
                    : Optional.empty();
        }
    }

    static String time(Instant instant, CheckinProperties properties) {
        return instant == null
                ? "?"
                : DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
                        .withZone(properties.timeZone())
                        .format(instant);
    }
}

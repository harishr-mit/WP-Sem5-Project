package com.mitpass.checkin;

import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.client.EventCatalog.GateInfo;
import com.mitpass.checkin.client.EventCatalog.SessionInfo;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.repository.PassCopyStore;
import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the world a scan needs: an event (LIVE by default) with gates MAIN and NORTH, a volunteer assigned to MAIN,
 * and passes in the copy. Tokens are signed by {@link TestPassKeys}, the stand-in for pass-service's key.
 */
@Component
public class ScanScenario {

    @Autowired
    FakeEventCatalog catalog;

    @Autowired
    FakePassDirectory directory;

    @Autowired
    PassCopyStore copy;

    @Autowired
    TestPassKeys keys;

    @Autowired
    TestClock clock;

    /** One event, its gates and its assigned volunteer. */
    public record Gate(EventInfo event, GateInfo main, GateInfo north, UUID volunteer, String volunteerToken) {

        public CurrentUser volunteerUser() {
            return new CurrentUser(
                    volunteer,
                    "v@mitpass.test",
                    "Volunteer",
                    Set.of(Roles.VOLUNTEER),
                    null,
                    null,
                    null,
                    volunteerToken);
        }
    }

    public Gate liveEvent() {
        return event("LIVE", false, null);
    }

    /** Starts 20 min from the clock (doors already open: the event is LIVE), ends 4 h later. */
    public Gate event(String status, boolean reentry, Integer minSessions, SessionInfo... sessions) {
        Instant now = clock.instant();
        EventInfo event = CheckinFixtures.event(
                status,
                now.plus(Duration.ofMinutes(20)),
                now.plus(Duration.ofHours(4)),
                reentry,
                minSessions,
                sessions);
        return staff(event);
    }

    public Gate staff(EventInfo event) {
        GateInfo main = CheckinFixtures.gate("MAIN");
        GateInfo north = CheckinFixtures.gate("NORTH");
        catalog.put(event, main, north);
        UUID volunteer = UUID.randomUUID();
        catalog.assign(volunteer, event, main);
        return new Gate(event, main, north, volunteer, TestJwtIssuer.tokenFor(volunteer, Roles.VOLUNTEER));
    }

    /** A pass in the copy (as if PassIssued had arrived) and at the owner. */
    public PassRecord pass(Gate gate, int seq, String holder) {
        PassRecord pass = CheckinFixtures.pass(gate.event(), seq, holder);
        directory.put(pass);
        copy.upsert(ref(pass), clock.instant());
        return pass;
    }

    /** A pass only the owner knows: no message reached this service. */
    public PassRecord ownerOnlyPass(Gate gate, int seq, String holder) {
        PassRecord pass = CheckinFixtures.pass(gate.event(), seq, holder);
        directory.put(pass);
        return pass;
    }

    public String liveToken(PassRecord pass) {
        return keys.live(pass.id(), pass.eventId(), pass.userId(), clock.instant());
    }

    public static PassRef ref(PassRecord pass) {
        return new PassRef(
                pass.id(),
                pass.passNumber(),
                pass.eventId(),
                pass.registrationId(),
                pass.userId(),
                pass.holderName(),
                pass.holderEmail(),
                pass.revoked() ? PassRef.REVOKED : PassRef.VALID,
                pass.issuedAt(),
                pass.revokedAt(),
                pass.revokeReason());
    }
}

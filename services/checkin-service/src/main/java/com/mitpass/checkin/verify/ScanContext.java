package com.mitpass.checkin.verify;

import com.mitpass.checkin.client.EventCatalog.EventInfo;
import com.mitpass.checkin.client.EventCatalog.GateInfo;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.domain.ScanMethod;
import com.mitpass.checkin.domain.ScanMode;
import com.mitpass.checkin.domain.SessionResolver.Resolution;
import com.mitpass.common.pass.PassTokenVerifier.VerifiedPassToken;
import com.mitpass.common.security.CurrentUser;
import java.time.Instant;
import java.util.UUID;

/**
 * One scan as it moves through the pipeline: the request, then whatever each step has established (gate and event,
 * the verified token or typed number, the session, the pass). Steps only add to it.
 */
public class ScanContext {

    /** What the volunteer sent. Exactly one of {@code token} / {@code passNumber}. */
    public record ScanCommand(
            String token, String passNumber, long gateId, String deviceId, Instant scannedAt, ScanMode mode) {

        public boolean manual() {
            return token == null;
        }
    }

    private final UUID scanId = UUID.randomUUID();
    private final CurrentUser volunteer;
    private final ScanCommand command;
    private final Instant now;
    private final boolean offline;

    GateInfo gate;
    EventInfo event;
    VerifiedPassToken token;
    String passNumber;
    Resolution session;
    PassRef pass;

    public ScanContext(CurrentUser volunteer, ScanCommand command, Instant now) {
        this(volunteer, command, now, false);
    }

    /** {@code offline}: a queued scan synced later; {@code now} is then the phone's time of the scan (P7). */
    public ScanContext(CurrentUser volunteer, ScanCommand command, Instant now, boolean offline) {
        this.volunteer = volunteer;
        this.command = command;
        this.now = now;
        this.offline = offline;
    }

    public boolean offline() {
        return offline;
    }

    public UUID scanId() {
        return scanId;
    }

    public CurrentUser volunteer() {
        return volunteer;
    }

    public ScanCommand command() {
        return command;
    }

    public Instant now() {
        return now;
    }

    public GateInfo gate() {
        return gate;
    }

    public EventInfo event() {
        return event;
    }

    public VerifiedPassToken token() {
        return token;
    }

    public Resolution session() {
        return session;
    }

    public PassRef pass() {
        return pass;
    }

    public ScanMethod method() {
        if (command.manual()) {
            return ScanMethod.MANUAL;
        }
        return token != null && token.isStatic() ? ScanMethod.QR_STATIC : ScanMethod.QR_LIVE;
    }

    /** The number as typed (normalized once the number step ran), or the copy's number for a token. */
    public String passNumber() {
        if (pass != null && pass.passNumber() != null) {
            return pass.passNumber();
        }
        return passNumber != null ? passNumber : command.passNumber();
    }
}

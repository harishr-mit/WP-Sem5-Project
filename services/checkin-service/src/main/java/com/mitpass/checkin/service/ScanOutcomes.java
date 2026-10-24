package com.mitpass.checkin.service;

import com.mitpass.checkin.config.CheckinProperties;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.checkin.domain.ScanLog;
import com.mitpass.checkin.domain.ScanResult;
import com.mitpass.checkin.service.ScanOutcome.Attendee;
import com.mitpass.checkin.verify.ScanContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds scan log rows and outcomes from what the pipeline learned, so every path reports the same way. */
@Component
public class ScanOutcomes {

    private final DateTimeFormatter clock;
    private final MeterRegistry meters;

    public ScanOutcomes(CheckinProperties properties, MeterRegistry meters) {
        this.clock = DateTimeFormatter.ofPattern("HH:mm").withZone(properties.timeZone());
        this.meters = meters;
    }

    /** {@code mitpass_scans_total{result}}: scans/min by result on the Grafana dashboard (D17). */
    private ScanResult counted(ScanResult result) {
        meters.counter("mitpass.scans", "result", result.name()).increment();
        return result;
    }

    String time(Instant instant) {
        return instant == null ? "?" : clock.format(instant);
    }

    ScanLog log(ScanContext scan, ScanResult result, String note) {
        PassRef pass = scan.pass();
        return new ScanLog(
                        scan.scanId(),
                        scan.now(),
                        CheckInTransaction.scannedAt(scan),
                        scan.volunteer().id(),
                        scan.command().deviceId(),
                        scan.command().gateId(),
                        scan.method(),
                        scan.command().mode())
                .outcome(
                        result,
                        scan.event() == null ? null : scan.event().id(),
                        pass != null
                                ? pass.passId()
                                : scan.token() != null ? scan.token().passId() : null,
                        scan.passNumber(),
                        scan.token() == null ? null : scan.token().jti(),
                        scan.session() != null && scan.session().open()
                                ? scan.session().sessionId()
                                : null,
                        note);
    }

    ScanOutcome outcome(
            ScanContext scan, ScanResult result, Map<String, String> details, Instant firstCheckIn, boolean reentry) {
        PassRef pass = scan.pass();
        Map<String, String> values = new HashMap<>(details);
        if (pass != null && pass.holderName() != null) {
            values.putIfAbsent("name", pass.holderName());
        }
        boolean sessionOpen = scan.session() != null && scan.session().open();
        return new ScanOutcome(
                scan.scanId(),
                counted(result),
                result.message(values),
                scan.event() == null ? null : scan.event().id(),
                scan.event() == null ? null : scan.event().code(),
                scan.gate() == null ? null : scan.gate().code(),
                sessionOpen ? scan.session().sessionId() : null,
                sessionOpen ? scan.session().title() : null,
                pass == null || pass.passNumber() == null
                        ? null
                        : new Attendee(pass.passId(), pass.passNumber(), pass.holderName()),
                firstCheckIn,
                reentry);
    }

    /** The same scan, answered with another result (an offline duplicate, P7). */
    ScanOutcome as(ScanOutcome outcome, ScanResult result) {
        return new ScanOutcome(
                outcome.scanId(),
                counted(result),
                result.message(
                        outcome.attendee() == null
                                ? Map.of()
                                : Map.of("name", outcome.attendee().holderName())),
                outcome.eventId(),
                outcome.eventCode(),
                outcome.gateCode(),
                outcome.sessionId(),
                outcome.sessionTitle(),
                outcome.attendee(),
                outcome.checkedInAt(),
                outcome.reentry());
    }

    /** The answer a resent offline scan got the first time (only its result is kept). */
    ScanOutcome replayed(ScanResult result) {
        return new ScanOutcome(null, result, result.message(Map.of()), null, null, null, null, null, null, null, false);
    }
}

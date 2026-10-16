package com.mitpass.checkin.service;

import com.mitpass.checkin.domain.Attendance;
import com.mitpass.checkin.domain.PassRef;
import com.mitpass.common.messaging.OutboxPublisher;
import com.mitpass.common.messaging.contract.AttendanceMarked;
import org.springframework.stereotype.Component;

/** The domain events a check-in produces, written to the outbox in the check-in transaction (D11). */
@Component
public class CheckinEvents {

    static final String AGGREGATE = "Attendance";

    private final OutboxPublisher outbox;

    public CheckinEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    void attendanceMarked(Attendance attendance, PassRef pass, boolean singleEntry) {
        outbox.publish(
                AGGREGATE,
                attendance.getId().toString(),
                new AttendanceMarked(
                        attendance.getId(),
                        pass.passId(),
                        pass.passNumber(),
                        pass.registrationId(),
                        attendance.getEventId(),
                        attendance.getSessionId(),
                        pass.userId(),
                        attendance.getGateId(),
                        attendance.getGateCode(),
                        attendance.getVolunteerId(),
                        attendance.getMethod().name(),
                        attendance.getCheckedInAt(),
                        attendance.getScannedAt(),
                        singleEntry));
    }
}

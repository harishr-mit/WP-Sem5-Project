package com.mitpass.checkin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.checkin.CheckinFixtures;
import com.mitpass.checkin.CheckinIntegrationTest;
import com.mitpass.checkin.ScanScenario;
import com.mitpass.checkin.ScanScenario.Gate;
import com.mitpass.checkin.TestClock;
import com.mitpass.checkin.client.PassDirectory.PassRecord;
import com.mitpass.common.security.Roles;
import com.mitpass.security.test.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** RL12 (D17): the event's organizer or an admin voids a wrong check-in; it stops counting and can be redone. */
@CheckinIntegrationTest
class VoidCheckInTest {

    static final String ORGANIZER = TestJwtIssuer.tokenFor(CheckinFixtures.ORGANIZER, Roles.ORGANIZER);

    @Autowired
    MockMvc mvc;

    @Autowired
    ScanScenario scenario;

    @Autowired
    TestClock clock;

    @Autowired
    JdbcClient jdbc;

    Gate gate;
    PassRecord pass;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        gate = scenario.liveEvent();
        pass = scenario.pass(gate, 1, "Wrong Person");
    }

    @Test
    void aVoidedCheckInStopsCountingIsAuditedAndThePassCanCheckInAgain() throws Exception {
        scan().andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ACCEPTED"));

        voidIt(ORGANIZER, "Scanned the wrong ticket")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionIds[0]").value(0))
                .andExpect(jsonPath("$.stillCheckedIn").value(false));

        assertThat(count("attendance")).isZero();
        assertThat(count("voided_attendance")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT void_reason FROM voided_attendance WHERE pass_id = :p")
                        .param("p", pass.id())
                        .query(String.class)
                        .single())
                .isEqualTo("Scanned the wrong ticket");
        assertThat(jdbc.sql("SELECT state FROM presence WHERE pass_id = :p")
                        .param("p", pass.id())
                        .query(String.class)
                        .single())
                .isEqualTo("NOT_ARRIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_message WHERE event_type = 'attendance.voided'"
                                + " AND payload -> 'payload' ->> 'passId' = :p")
                        .param("p", pass.id().toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        mvc.perform(get("/api/attendance/events/{id}/summary", gate.event().id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ORGANIZER))
                .andExpect(jsonPath("$.checkedIn").value(0));

        scan().andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ACCEPTED"));
        assertThat(count("attendance")).isEqualTo(1);
    }

    @Test
    void volunteersCantVoidAndNothingToVoidIsA404() throws Exception {
        voidIt(ORGANIZER, "No scan yet").andExpect(status().isNotFound());
        scan().andExpect(status().isOk());
        voidIt(gate.volunteerToken(), "Not mine to undo").andExpect(status().isForbidden());
        voidIt(ORGANIZER, " ").andExpect(status().isBadRequest());
        assertThat(count("attendance")).isEqualTo(1);
    }

    private ResultActions scan() throws Exception {
        return mvc.perform(post("/api/scans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + gate.volunteerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(ScanVerificationTest.body(
                        scenario.liveToken(pass), null, gate.main().id(), "IN")));
    }

    private ResultActions voidIt(String token, String reason) throws Exception {
        return mvc.perform(post("/api/attendance/events/{id}/void", gate.event().id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passId\":\"%s\",\"reason\":\"%s\"}".formatted(pass.id(), reason)));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE pass_id = :p")
                .param("p", pass.id())
                .query(Long.class)
                .single();
    }
}

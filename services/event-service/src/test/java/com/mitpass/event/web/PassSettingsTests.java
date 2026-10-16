package com.mitpass.event.web;

import static com.mitpass.event.web.LifecycleTests.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.event.EventIntegrationTest;
import com.mitpass.event.validation.PassColorValidator;
import com.mitpass.security.test.TestJwtIssuer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-event pass settings and {@code EventRescheduled} (docs/plans/phase-4.md §6, §8; D12/D13). pass-service reads the
 * settings from the event detail and follows reschedules through the outbox.
 */
@EventIntegrationTest
class PassSettingsTests {

    static final AtomicInteger CODES = new AtomicInteger();
    static final UUID ORGANIZER = UUID.randomUUID();
    static final String OWNER = TestJwtIssuer.tokenFor(ORGANIZER, Roles.ORGANIZER);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void defaultsAreStaticQrOnAndNoColour() throws Exception {
        long id = create(null, null);

        mvc.perform(get("/api/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staticQrAllowed").value(true))
                .andExpect(jsonPath("$.passColor").doesNotExist());
    }

    @Test
    void colourIsStoredUpperCaseAndStaticQrCanBeSwitchedOff() throws Exception {
        long id = create("\"#1d4e89\"", "false");

        mvc.perform(get("/api/events/{id}", id))
                .andExpect(jsonPath("$.staticQrAllowed").value(false))
                .andExpect(jsonPath("$.passColor").value("#1D4E89"));
    }

    @Test
    void lowContrastOrMalformedColoursAreRejected() throws Exception {
        for (String colour : new String[] {"\"#FFFF00\"", "\"#ABC\"", "\"red\""}) {
            mvc.perform(as(OWNER, post("/api/events")).content(body(nextCode(), 0, colour, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("passColor"));
        }
    }

    @Test
    void updateWithoutTheFlagKeepsIt() throws Exception {
        long id = create(null, "false");
        String code = code(id);

        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(body(code, 0, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staticQrAllowed").value(false));
        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(body(code, 0, null, "true")))
                .andExpect(jsonPath("$.staticQrAllowed").value(true));
    }

    @Test
    void reschedulingAPublishedEventPublishesEventRescheduled() throws Exception {
        long id = create(null, null);
        String code = code(id);
        mvc.perform(as(OWNER, post("/api/events/{id}/transitions", id)).content("{\"to\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        // Same times: nothing to tell pass-service.
        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(body(code, 1, null, null)))
                .andExpect(status().isOk());
        assertThat(rescheduledRows(id)).isZero();

        mvc.perform(as(OWNER, put("/api/events/{id}", id)).content(body(code, 1, null, null, "2099-11-14T05:30:00Z")))
                .andExpect(status().isOk());

        assertThat(rescheduledRows(id)).isEqualTo(1);
        String payload = jdbc.sql("SELECT payload::text FROM outbox_message WHERE event_type = 'event.rescheduled'"
                        + " AND aggregate_id = :id")
                .param("id", String.valueOf(id))
                .query(String.class)
                .single();
        assertThat((String) JsonPath.read(payload, "$.payload.startsAt")).isEqualTo("2099-11-14T05:30:00Z");
        assertThat((String) JsonPath.read(payload, "$.payload.changedBy")).isEqualTo(ORGANIZER.toString());
    }

    @Test
    void reschedulingADraftPublishesNothing() throws Exception {
        long id = create(null, null);

        mvc.perform(as(OWNER, put("/api/events/{id}", id))
                        .content(body(code(id), 0, null, null, "2099-11-14T05:30:00Z")))
                .andExpect(status().isOk());

        assertThat(rescheduledRows(id)).isZero();
    }

    @Test
    void contrastRatioMatchesWcag() {
        assertThat(PassColorValidator.contrastAgainstWhite("#000000")).isCloseTo(21.0, within());
        assertThat(PassColorValidator.contrastAgainstWhite("#FFFFFF")).isCloseTo(1.0, within());
        assertThat(PassColorValidator.contrastAgainstWhite("#7A1F2B")).isGreaterThan(4.5);
        assertThat(PassColorValidator.contrastAgainstWhite("#767676")).isCloseTo(4.54, within());
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.01);
    }

    private long create(String colour, String staticQr) throws Exception {
        String response = mvc.perform(as(OWNER, post("/api/events")).content(body(nextCode(), 0, colour, staticQr)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private String code(long id) throws Exception {
        String response = mvc.perform(get("/api/events/{id}", id))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.code");
    }

    private int rescheduledRows(long id) {
        return jdbc.sql("SELECT count(*) FROM outbox_message WHERE event_type = 'event.rescheduled'"
                        + " AND aggregate_id = :id")
                .param("id", String.valueOf(id))
                .query(Integer.class)
                .single();
    }

    private static String nextCode() {
        return "PSET" + CODES.incrementAndGet();
    }

    private static String body(String code, int capacityOffset, String colour, String staticQr) {
        return body(code, capacityOffset, colour, staticQr, "2099-11-14T03:30:00Z");
    }

    private static String body(String code, int capacityOffset, String colour, String staticQr, String startsAt) {
        return """
                {"code":"%s","title":"Pass settings %s","categoryId":1,"venueId":1,"capacity":%d,
                 "startsAt":"%s","endsAt":"2099-11-14T09:30:00Z",
                 "registrationOpensAt":"2099-10-01T03:30:00Z","registrationClosesAt":"2099-11-13T12:30:00Z",
                 "passColor":%s,"staticQrAllowed":%s}
                """.formatted(code, code, 40 + capacityOffset, startsAt, colour, staticQr);
    }
}

package com.mitpass.common.web.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.common.web.CommonWebAutoConfiguration;
import com.mitpass.common.web.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProbeController.class)
@Import(CommonWebAutoConfiguration.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void invalidBodyIsA400WithFieldErrors() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"size\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:validation"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("2 fields are invalid"))
                .andExpect(jsonPath("$.instance").value("/probe"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[1].field").value("size"));
    }

    @Test
    void invalidRequestParamIsA400WithFieldErrors() throws Exception {
        mvc.perform(get("/probe/limit").param("value", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("value"));
    }

    @Test
    void unreadableJsonIsA400MalformedRequest() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:malformed-request"));
    }

    @Test
    void typeMismatchIsA400MalformedRequest() throws Exception {
        mvc.perform(get("/probe/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:malformed-request"))
                .andExpect(jsonPath("$.detail").value(containsString("'id'")));
    }

    @Test
    void notFoundIsA404() throws Exception {
        mvc.perform(get("/probe/7"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:not-found"))
                .andExpect(jsonPath("$.detail").value("Probe 7 was not found"));
    }

    @ParameterizedTest
    @CsvSource({
        "conflict,  409, urn:mitpass:problem:conflict",
        "integrity, 409, urn:mitpass:problem:conflict",
        "stale,     409, urn:mitpass:problem:conflict",
        "rule,      422, urn:mitpass:problem:business-rule",
        "boom,      500, urn:mitpass:problem:internal"
    })
    void domainAndUnexpectedErrorsMapToStatus(String kind, int status, String type) throws Exception {
        mvc.perform(get("/probe/fail/" + kind))
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(type));
    }

    @Test
    void problemExceptionKeepsItsStatusTypeAndHeaders() throws Exception {
        mvc.perform(get("/probe/fail/locked"))
                .andExpect(status().isLocked())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:account-locked"))
                .andExpect(jsonPath("$.title").value("Account locked"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void illegalTransitionIsA409WithTheAllowedStates() throws Exception {
        mvc.perform(get("/probe/fail/transition"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:illegal-transition"))
                .andExpect(jsonPath("$.currentState").value("DRAFT"))
                .andExpect(jsonPath("$.requestedState").value("LIVE"))
                .andExpect(jsonPath("$.allowedStates[0]").value("PUBLISHED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void problemExceptionPropertiesBecomeExtensionMembers() throws Exception {
        mvc.perform(get("/probe/fail/extended"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:not-eligible"))
                .andExpect(jsonPath("$.reasons[0]").value("Open to CSE only"));
    }

    @Test
    void internalErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/probe/fail/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @Test
    void sqlDetailsAreNotLeaked() throws Exception {
        mvc.perform(get("/probe/fail/integrity")).andExpect(content().string(not(containsString("unique constraint"))));
    }

    @Test
    void correlationIdFromTheGatewayIsEchoedInHeaderAndBody() throws Exception {
        mvc.perform(get("/probe/7").header(CorrelationIdFilter.HEADER, "abc-123"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.correlationId").value("abc-123"));
    }

    @Test
    void unknownPathIsA404Problem() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:not-found"));
    }
}

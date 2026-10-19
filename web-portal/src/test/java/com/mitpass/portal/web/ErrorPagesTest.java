package com.mitpass.portal.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import jakarta.servlet.RequestDispatcher;
import java.util.List;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

/**
 * The portal's own error pages (PROJECT.md §P6: custom 403/404/500, plus 503 when a backend is down). Each shows a
 * reference (the correlation id) and never a stack trace or exception name. MockMvc doesn't perform the servlet
 * container's error dispatch, so container errors (Spring Security's 403, an unknown URL's 404) are tested by calling
 * {@code /error} with the attributes the container would set, and with a browser's {@code Accept: text/html} (without
 * it Spring Boot answers JSON).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorPagesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    static Matcher<String> noInternals() {
        return allOf(
                not(containsString("Exception")),
                not(containsString("at com.mitpass")),
                not(containsString("Whitelabel")),
                not(containsString("trace")));
    }

    @Test
    void anEventTheServiceDoesNotKnowShowsTheNotFoundPage() throws Exception {
        given(gateway.event(99L))
                .willThrow(new ApiProblemException(
                        404, "urn:mitpass:problem:not-found", "Event 99 not found", null, null));

        mvc.perform(get("/events/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Not found")))
                .andExpect(content().string(containsString("Reference: portal-")))
                .andExpect(content().string(noInternals()));
    }

    @Test
    void aForbiddenCallShowsTheForbiddenPageWithTheUserStillLoggedIn() throws Exception {
        given(gateway.event(5L))
                .willThrow(new ApiProblemException(403, "urn:mitpass:problem:forbidden", "nope", null, null));

        mvc.perform(get("/events/5").with(authentication(TestTokens.login(TestTokens.user("STUDENT")))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Not allowed")))
                .andExpect(content().string(containsString("Priya Raman")))
                .andExpect(content().string(noInternals()));
    }

    @Test
    void anUnreachableBackendShowsTheUnavailablePage() throws Exception {
        given(gateway.search(any())).willThrow(new ResourceAccessException("Connection refused"));
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));

        mvc.perform(get("/events"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("Back in a moment")))
                .andExpect(content().string(not(containsString("Connection refused"))))
                .andExpect(content().string(noInternals()));
    }

    @Test
    void anyOtherProblemShowsTheErrorPageWithTheServicesMessage() throws Exception {
        given(gateway.event(7L))
                .willThrow(
                        new ApiProblemException(409, "urn:mitpass:problem:conflict", "The event changed", null, null));

        mvc.perform(get("/events/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Something went wrong")))
                .andExpect(content().string(containsString("The event changed")))
                .andExpect(content().string(noInternals()));
    }

    @Test
    void containerErrorsUseTheTemplates() throws Exception {
        mvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/organizer/events"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Not allowed")))
                .andExpect(content().string(noInternals()));
        mvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/no/such/page"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Not found")));
        mvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret detail"))
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/events"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Something went wrong")))
                .andExpect(content().string(not(containsString("secret detail"))))
                .andExpect(content().string(noInternals()));
        mvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 418)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/teapot"))
                .andExpect(status().is(418))
                .andExpect(content().string(containsString("Error 418")));
    }
}

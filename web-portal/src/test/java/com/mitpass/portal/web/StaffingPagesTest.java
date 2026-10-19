package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.Volunteer;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Gates, volunteer assignment and the registrations table (T08.7). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffingPagesTest {

    static final UUID KARTHIK = UUID.fromString("00000000-0000-4000-8000-000000000004");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser organizer = TestTokens.user("ORGANIZER");

    @BeforeEach
    void event() {
        given(gateway.event(1L)).willReturn(PortalPagesTest.detail(1, List.of(), "REGISTRATION_OPEN", List.of()));
        given(gateway.gates(1L)).willReturn(List.of(new Gate(11L, "MAIN", "Main Gate")));
        given(gateway.eventVolunteers(1L))
                .willReturn(List.of(new Volunteer(
                        KARTHIK,
                        "Karthik Selvam",
                        "volunteer@mitpass.test",
                        new Gate(11L, "MAIN", "Main Gate"),
                        Instant.now())));
    }

    @Test
    void theStaffPageListsGatesAndVolunteersWithTwoEmptyForms() throws Exception {
        mvc.perform(get("/organizer/events/1/staff").with(authentication(TestTokens.login(organizer))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Main Gate")))
                .andExpect(content().string(containsString("Karthik Selvam")))
                .andExpect(content().string(containsString("Assign a volunteer")));
    }

    @Test
    void aBadGateCodeIsShownOnItsFieldAndTheOtherFormStaysEmpty() throws Exception {
        mvc.perform(post("/organizer/events/1/gates")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf())
                        .param("code", "m"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("gateForm", "code", "name"))
                .andExpect(model().attributeHasNoErrors("assignForm"));
        verify(gateway, never()).addGate(eq(1L), anyString(), any());
    }

    @Test
    void addingAGateUpperCasesTheCodeAndRedirects() throws Exception {
        given(gateway.addGate(1L, "NORTH", "North Gate")).willReturn(new Gate(12L, "NORTH", "North Gate"));

        mvc.perform(post("/organizer/events/1/gates")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf())
                        .param("code", "north")
                        .param("name", "North Gate"))
                .andExpect(redirectedUrl("/organizer/events/1/staff"))
                .andExpect(flash().attribute("message", "Gate NORTH added."));
    }

    @Test
    void notAVolunteerIsShownOnTheEmailField() throws Exception {
        given(gateway.assignVolunteer(eq(1L), eq("priya@gmail.com"), isNull()))
                .willThrow(new ApiProblemException(
                        422, "urn:mitpass:problem:business-rule", "priya@gmail.com is not a volunteer", null, null));

        mvc.perform(post("/organizer/events/1/volunteers")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf())
                        .param("email", "priya@gmail.com")
                        .param("gateId", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("assignForm", "email"))
                .andExpect(content().string(containsString("is not a volunteer")));
    }

    @Test
    void assigningToOneGateRedirectsWithTheName() throws Exception {
        given(gateway.assignVolunteer(1L, "volunteer@mitpass.test", 11L))
                .willReturn(new Volunteer(KARTHIK, "Karthik Selvam", "volunteer@mitpass.test", null, Instant.now()));

        mvc.perform(post("/organizer/events/1/volunteers")
                        .with(authentication(TestTokens.login(organizer)))
                        .with(csrf())
                        .param("email", " volunteer@mitpass.test ")
                        .param("gateId", "11"))
                .andExpect(redirectedUrl("/organizer/events/1/staff"))
                .andExpect(flash().attribute("message", "Karthik Selvam is assigned."));
    }

    @Test
    void volunteersCannotManageStaff() throws Exception {
        mvc.perform(get("/organizer/events/1/staff")
                        .with(authentication(TestTokens.login(TestTokens.user("VOLUNTEER")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationsCountAndFilterWithRevokeDialogs() throws Exception {
        var confirmed = PortalPagesTest.registration("CONFIRMED", null, null);
        given(gateway.eventRegistrations(1L)).willReturn(new Page<>(List.of(confirmed), 0, 100, 1, 1));
        Pass pass = EventPagesTest.pass();
        given(gateway.eventPasses(1L)).willReturn(new Page<>(List.of(pass), 0, 100, 1, 1));

        mvc.perform(get("/organizer/events/1/registrations").with(authentication(TestTokens.login(organizer))))
                .andExpect(content().string(containsString("data-open-dialog=\"revoke-" + pass.id())))
                .andExpect(content().string(containsString("Passes revoked")));
        mvc.perform(get("/organizer/events/1/registrations")
                        .param("q", "nobody-like-this")
                        .header("HX-Request", "true")
                        .with(authentication(TestTokens.login(organizer))))
                .andExpect(content().string(containsString("Nobody matches")))
                .andExpect(content().string(not(containsString("<html"))));
        mvc.perform(get("/organizer/events/1/registrations")
                        .param("q", "MINIQUIZ26-000001")
                        .with(authentication(TestTokens.login(organizer))))
                .andExpect(content().string(containsString("Priya Raman")));
    }
}

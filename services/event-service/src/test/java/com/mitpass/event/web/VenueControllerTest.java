package com.mitpass.event.web;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.common.security.Roles;
import com.mitpass.common.web.CommonWebAutoConfiguration;
import com.mitpass.common.web.error.ConflictException;
import com.mitpass.event.service.VenueService;
import com.mitpass.event.web.dto.VenueResponse;
import com.mitpass.security.test.TestJwtIssuer;
import com.mitpass.security.test.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VenueController.class)
@Import({CommonWebAutoConfiguration.class, TestSecurityConfig.class})
class VenueControllerTest {

    /** Writes need a token now (Phase 2); these tests are about behaviour, so they act as ADMIN. */
    static final String ADMIN = "Bearer " + TestJwtIssuer.token(Roles.ADMIN);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VenueService venues;

    @Test
    void createReturns201() throws Exception {
        given(venues.create(any())).willReturn(new VenueResponse(9L, "Mini Auditorium", null, 300, null, 0L));

        mvc.perform(post("/api/venues")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mini Auditorium\",\"capacity\":300}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/venues/9"));
    }

    @Test
    void invalidVenueIsA400() throws Exception {
        mvc.perform(post("/api/venues")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"capacity\":-5,\"mapUrl\":\"not a url\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("capacity")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("mapUrl")));
    }

    @Test
    void duplicateNameIsA409() throws Exception {
        given(venues.create(any())).willThrow(new ConflictException("A venue named 'Main Auditorium' already exists"));

        mvc.perform(post("/api/venues")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"main auditorium\",\"capacity\":300}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:conflict"));
    }
}

package com.mitpass.auth.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.auth.config.AppConfig;
import com.mitpass.auth.config.AuthProperties;
import com.mitpass.auth.keys.SigningKeyService;
import com.mitpass.auth.service.AuthProblems;
import com.mitpass.auth.service.EmailPolicy;
import com.mitpass.auth.service.EmailVerificationService;
import com.mitpass.auth.service.LoginService;
import com.mitpass.auth.service.RefreshTokenService;
import com.mitpass.auth.service.SignupService;
import com.mitpass.common.security.Roles;
import com.mitpass.common.web.CommonWebAutoConfiguration;
import com.mitpass.security.test.TestJwtIssuer;
import com.mitpass.security.test.TestSecurityConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Request validation and error mapping of the auth endpoints (services mocked). */
@WebMvcTest(AuthController.class)
@Import({TestSecurityConfig.class, CommonWebAutoConfiguration.class, EmailPolicy.class, AppConfig.class})
class AuthControllerTest {

    /** Slices don't bind @ConfigurationProperties records found by scanning; EmailPolicy needs AuthProperties. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class Properties {}

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SignupService signup;

    @MockitoBean
    EmailVerificationService verification;

    @MockitoBean
    LoginService login;

    @MockitoBean
    RefreshTokenService sessions;

    @MockitoBean
    SigningKeyService signingKeys;

    @Test
    void signupReportsEachProblemOnItsField() throws Exception {
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"fullName":"","email":"2026509032@student.annauniv.edu","password":"short",
                         "department":"CSE","year":9,"registerNumber":"2026509099"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:validation"))
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'year')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'registerNumber')].message")
                        .value("must match the register number in your email (2026509032)"));
    }

    @Test
    void signupRejectsOtherDomains() throws Exception {
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"fullName":"Priya","email":"priya@yahoo.com","password":"Techfest2026","department":"CSE"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be an address ending in @student.annauniv.edu or @gmail.com"));
    }

    @Test
    void passwordMayNotContainTheEmailName() throws Exception {
        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"fullName":"Priya","email":"priyaraman@gmail.com","password":"PriyaRaman1","department":"CSE"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void lockedLoginIs423WithRetryAfter() throws Exception {
        Instant now = Instant.now();
        given(login.login(anyString(), anyString(), any()))
                .willThrow(AuthProblems.accountLocked(now.plusSeconds(600), now));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@gmail.com\",\"password\":\"x\"}"))
                .andExpect(status().isLocked())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:account-locked"));
    }

    @Test
    void keyRotationIsForAdminsOnly() throws Exception {
        given(signingKeys.rotate()).willReturn("new-kid");

        mvc.perform(post("/api/auth/keys/rotate")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/keys/rotate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.ORGANIZER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/keys/rotate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtIssuer.token(Roles.ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kid").value("new-kid"));
    }
}

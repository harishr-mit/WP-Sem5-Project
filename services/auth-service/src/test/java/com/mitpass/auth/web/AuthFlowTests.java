package com.mitpass.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mitpass.auth.AuthIntegrationTest;
import com.mitpass.auth.CapturingMailSender;
import com.mitpass.auth.TestAccounts;
import com.mitpass.auth.domain.RoleName;
import com.mitpass.auth.domain.User;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The auth API end to end over HTTP (MockMvc), against PostgreSQL. */
@AuthIntegrationTest
class AuthFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    CapturingMailSender mail;

    @Autowired
    TestAccounts accounts;

    @Test
    void signUpVerifyLoginRefreshAndCatchReuse() throws Exception {
        String regNo = "2026" + (100000 + (int) (Math.random() * 899999));
        String email = regNo + "@student.annauniv.edu";

        signup(email, "Techfest2026")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("/api/users/")))
                .andExpect(jsonPath("$.registerNumber").value(regNo))
                .andExpect(jsonPath("$.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.emailVerified").value(false));

        // Not verified yet: the right password gets 403, not a token.
        login(email, "Techfest2026")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:email-not-verified"));

        String token = await().atMost(Duration.ofSeconds(10))
                .until(() -> mail.latestTokenFor(email).orElse(null), t -> t != null);
        mvc.perform(get("/api/auth/verify-email").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
        mvc.perform(get("/api/auth/verify-email").param("token", token)).andExpect(status().isOk());

        String loginBody = login(email, "Techfest2026")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String access = JsonPath.read(loginBody, "$.accessToken");
        String refresh = JsonPath.read(loginBody, "$.refreshToken");

        // The token's kid is in the public JWK set, and its claims describe the user.
        SignedJWT jwt = SignedJWT.parse(access);
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[*].kid", hasItem(jwt.getHeader().getKeyID())));
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("STUDENT");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("regNo")).isEqualTo(regNo);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("dept")).isEqualTo("CSE");

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true));

        String rotated = JsonPath.read(
                refresh(refresh)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.refreshToken");

        refresh(refresh)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:refresh-token-reused"));
        refresh(rotated)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:invalid-refresh-token"));
    }

    /** Phase 6 (D9 row): the email's link opens the portal's confirmation page, not this service's API. */
    @Test
    void theVerificationLinkOpensThePortalPage() throws Exception {
        String email = TestAccounts.uniqueGmail();
        signup(email, "Techfest2026").andExpect(status().isCreated());

        String link = await().atMost(Duration.ofSeconds(10))
                .until(() -> mail.latestLinkFor(email).orElse(null), l -> l != null);
        assertThat(link).startsWith("http://localhost:8090/verify-email?token=");
        assertThat(link).doesNotContain("/api/");
    }

    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        String email = TestAccounts.uniqueGmail();
        accounts.verifiedUser(email, RoleName.STUDENT);

        for (int i = 0; i < 4; i++) {
            login(email, "wrong-pass1").andExpect(status().isUnauthorized());
        }
        login(email, "wrong-pass1")
                .andExpect(status().isLocked())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:account-locked"));
        login(email, TestAccounts.PASSWORD).andExpect(status().isLocked());
    }

    @Test
    void signUpRulesAreEnforced() throws Exception {
        signup("priya@yahoo.com", "Techfest2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        signup("priya.raman@student.annauniv.edu", "Techfest2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        String id = UUID.randomUUID().toString().substring(0, 8);
        signup("dup." + id + "@gmail.com", "Techfest2026").andExpect(status().isCreated());
        // Same Gmail inbox: dots and +tags don't make a new address.
        signup("d.u.p" + id + "+fest@gmail.com", "Techfest2026").andExpect(status().isConflict());
    }

    /** RL10: a banned account can't log in or refresh; nobody bans themselves; unbanning restores the login. */
    @Test
    void anAdminBansAndUnbansAUser() throws Exception {
        User admin = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.ADMIN);
        User student = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT);
        String adminToken = accessTokenOf(admin.getEmail());
        String studentRefresh = JsonPath.read(
                login(student.getEmail(), TestAccounts.PASSWORD)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.refreshToken");

        mvc.perform(post("/api/users/{id}/ban", student.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banned").value(true));
        login(student.getEmail(), TestAccounts.PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:account-disabled"));
        refresh(studentRefresh).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/users/{id}/ban", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(post("/api/users/{id}/unban", student.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(jsonPath("$.banned").value(false));
        login(student.getEmail(), TestAccounts.PASSWORD).andExpect(status().isOk());
    }

    @Test
    void adminChangesRolesAndTheUsersSessionsEnd() throws Exception {
        User admin = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.ADMIN);
        User student = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT);
        String adminToken = accessTokenOf(admin.getEmail());
        String studentRefresh = JsonPath.read(
                login(student.getEmail(), TestAccounts.PASSWORD)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.refreshToken");

        mvc.perform(put("/api/users/{id}/roles", student.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"STUDENT\",\"VOLUNTEER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("VOLUNTEER")));

        refresh(studentRefresh).andExpect(status().isUnauthorized());
        String newToken = accessTokenOf(student.getEmail());
        assertThat(SignedJWT.parse(newToken).getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactlyInAnyOrder("STUDENT", "VOLUNTEER");

        // An admin can't demote themselves.
        mvc.perform(put("/api/users/{id}/roles", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"STUDENT\"]}"))
                .andExpect(status().isUnprocessableContent());
    }

    /** RL11 (owner, 2026-10-16): every organizer is also a volunteer, automatically. */
    @Test
    void makingSomeoneAnOrganizerMakesThemAVolunteerToo() throws Exception {
        User admin = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.ADMIN);
        User user = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.STUDENT);
        String adminToken = accessTokenOf(admin.getEmail());

        mvc.perform(put("/api/users/{id}/roles", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ORGANIZER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("ORGANIZER")))
                .andExpect(jsonPath("$.roles", hasItem("VOLUNTEER")));

        assertThat(SignedJWT.parse(accessTokenOf(user.getEmail()))
                        .getJWTClaimsSet()
                        .getStringListClaim("roles"))
                .containsExactlyInAnyOrder("ORGANIZER", "VOLUNTEER");
    }

    @Test
    void keyRotationKeepsOldTokensValid() throws Exception {
        User admin = accounts.verifiedUser(TestAccounts.uniqueGmail(), RoleName.ADMIN);
        String before = accessTokenOf(admin.getEmail());

        mvc.perform(post("/api/auth/keys/rotate").header(HttpHeaders.AUTHORIZATION, "Bearer " + before))
                .andExpect(status().isCreated());
        String after = accessTokenOf(admin.getEmail());

        assertThat(SignedJWT.parse(after).getHeader().getKeyID())
                .isNotEqualTo(SignedJWT.parse(before).getHeader().getKeyID());
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + before))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + after))
                .andExpect(status().isOk());
    }

    private String accessTokenOf(String email) throws Exception {
        return JsonPath.read(
                login(email, TestAccounts.PASSWORD)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.accessToken");
    }

    private ResultActions signup(String email, String password) throws Exception {
        return mvc.perform(
                post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Priya Raman","email":"%s","password":"%s","department":"CSE","year":1}
                """.formatted(
                                email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }
}

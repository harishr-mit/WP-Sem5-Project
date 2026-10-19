package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Sign-up follow-up and email verification in the portal (D9 row: the email's link opens a portal page). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountPagesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    @Test
    void checkYourInboxShowsTheAddressAndTheDevMailbox() throws Exception {
        mvc.perform(get("/check-email").flashAttr("signedUpEmail", "priya@gmail.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<strong>priya@gmail.com</strong>")))
                .andExpect(content().string(containsString("http://mailpit.test")));
    }

    @Test
    void theAddressIsEscapedWhereTheMessageAllowsMarkup() throws Exception {
        mvc.perform(get("/check-email").flashAttr("signedUpEmail", "<script>x</script>@gmail.com"))
                .andExpect(content().string(not(containsString("<script>x"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void theEmailLinkOnlyShowsAButtonAndSpendsNothing() throws Exception {
        mvc.perform(get("/verify-email").param("token", "tok-123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirm my email")))
                .andExpect(content().string(containsString("value=\"tok-123\"")));
        verify(gateway, never()).verifyEmail(anyString());
    }

    @Test
    void confirmingVerifiesAndGoesToLogin() throws Exception {
        mvc.perform(post("/verify-email").with(csrf()).param("token", "tok-123"))
                .andExpect(redirectedUrl("/login?verified"));
        verify(gateway).verifyEmail("tok-123");

        mvc.perform(get("/login").param("verified", ""))
                .andExpect(content().string(containsString("Your email is verified")));
    }

    @Test
    void aBadLinkSaysSoAndOffersANewOne() throws Exception {
        willThrow(new ApiProblemException(400, "urn:mitpass:problem:invalid-token", "Invalid token", null, null))
                .given(gateway)
                .verifyEmail("expired");

        mvc.perform(post("/verify-email").with(csrf()).param("token", "expired"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("invalid, already used, or has expired")))
                .andExpect(content().string(containsString("Send a new link")));
        mvc.perform(get("/verify-email")).andExpect(content().string(containsString("incomplete")));
    }

    @Test
    void resendingGivesTheSameAnswerWhetherOrNotTheAddressExists() throws Exception {
        mvc.perform(post("/verify-email/resend").with(csrf()).param("email", "priya@gmail.com"))
                .andExpect(redirectedUrl("/check-email"))
                .andExpect(flash().attribute("resentTo", "priya@gmail.com"));
        willThrow(new ApiProblemException(400, null, "bad", null, null))
                .given(gateway)
                .resendVerification("nobody");
        mvc.perform(post("/verify-email/resend").with(csrf()).param("email", "nobody"))
                .andExpect(redirectedUrl("/check-email"))
                .andExpect(flash().attribute("resentTo", "nobody"));
        verify(gateway).resendVerification("priya@gmail.com");
    }

    @Test
    void anUnverifiedLoginOffersTheResendForm() throws Exception {
        org.mockito.BDDMockito.given(gateway.login(anyString(), anyString()))
                .willThrow(new ApiProblemException(403, "urn:mitpass:problem:email-not-verified", "x", null, null));

        var session = mvc.perform(
                        post("/login").with(csrf()).param("email", "a@b.c").param("password", "p"))
                .andReturn()
                .getRequest()
                .getSession();
        mvc.perform(get("/login").param("error", "").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(content().string(containsString("Send a new link")));
    }
}

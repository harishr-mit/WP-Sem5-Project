package com.mitpass.portal.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.WebMvcConfig;
import com.mitpass.portal.web.support.CurrentUserInterceptor;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The page shell (T08.2): the current user comes from a HandlerInterceptor, the theme and language switches work and
 * are remembered in cookies, and the theme form can't be used as an open redirect.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShellTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    final PortalUser student = TestTokens.user("STUDENT");

    @BeforeEach
    void catalogue() {
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));
        given(gateway.search(any())).willReturn(new Page<>(List.of(), 0, 12, 0, 0));
    }

    @Test
    void theInterceptorPutsTheCurrentUserIntoEveryPage() throws Exception {
        mvc.perform(get("/events").with(authentication(TestTokens.login(student))))
                .andExpect(model().attribute("currentUser", student))
                .andExpect(content().string(containsString("Log out")))
                .andExpect(content().string(containsString(">PR<"))); // initials in the avatar
        mvc.perform(get("/events"))
                .andExpect(model().attribute("currentUser", nullValue()))
                .andExpect(content().string(containsString("Sign up")));
    }

    @Test
    void theNavigationFollowsTheRoles() throws Exception {
        mvc.perform(get("/events").with(authentication(TestTokens.login(student))))
                .andExpect(content().string(containsString("My passes")))
                .andExpect(content().string(not(containsString("My events"))))
                .andExpect(content().string(not(containsString(">Users<"))));
        mvc.perform(get("/events").with(authentication(TestTokens.login(TestTokens.user("ADMIN")))))
                .andExpect(content().string(containsString(">Users<")))
                .andExpect(content().string(containsString(">Health<")))
                .andExpect(content().string(not(containsString("My passes"))));
    }

    @Test
    void theThemeChoiceIsAFormPostRememberedInACookie() throws Exception {
        mvc.perform(post("/preferences/theme")
                        .with(csrf())
                        .param("theme", "dark")
                        .param("back", "/events?q=quiz"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/events?q=quiz"))
                .andExpect(cookie().value(CurrentUserInterceptor.THEME_COOKIE, "dark"))
                .andExpect(cookie().httpOnly(CurrentUserInterceptor.THEME_COOKIE, true));

        mvc.perform(get("/login").cookie(new Cookie(CurrentUserInterceptor.THEME_COOKIE, "dark")))
                .andExpect(content().string(containsString("data-theme=\"dark\"")));
        mvc.perform(get("/login").cookie(new Cookie(CurrentUserInterceptor.THEME_COOKIE, "neon")))
                .andExpect(content().string(not(containsString("data-theme="))));
    }

    @Test
    void theThemeFormNeedsCsrfAndOnlyRedirectsWithinThePortal() throws Exception {
        mvc.perform(post("/preferences/theme").param("theme", "dark")).andExpect(status().isForbidden());
        for (String evil : List.of("//evil.example", "https://evil.example", "/\\evil.example", "events")) {
            mvc.perform(post("/preferences/theme")
                            .with(csrf())
                            .param("theme", "light")
                            .param("back", evil))
                    .andExpect(redirectedUrl("/"));
        }
    }

    @Test
    void theLanguageSwitchIsRememberedAndKeepsTheQuery() throws Exception {
        mvc.perform(get("/login").param("expired", "1").param("lang", "ta"))
                .andExpect(status().isOk())
                .andExpect(cookie().value(WebMvcConfig.LANG_COOKIE, "ta"))
                .andExpect(content().string(containsString("lang=\"ta\"")))
                // the switch back to English keeps the search
                .andExpect(content().string(containsString("/login?expired=1&amp;lang=en")));
    }

    @Test
    void theBrowserLanguageDecidesWhenNothingWasChosenAndUnknownLanguagesAreIgnored() throws Exception {
        mvc.perform(get("/login").header("Accept-Language", "ta-IN,ta;q=0.9"))
                .andExpect(content().string(containsString("lang=\"ta\"")));
        mvc.perform(get("/login").header("Accept-Language", "de-DE"))
                .andExpect(content().string(containsString("lang=\"en\"")));
        mvc.perform(get("/login").param("lang", "xx"))
                .andExpect(cookie().doesNotExist(WebMvcConfig.LANG_COOKIE))
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void staticFilesAreServedPubliclyWithVersionedUrls() throws Exception {
        mvc.perform(get("/events"))
                .andExpect(content().string(containsString("/css/tokens-")))
                .andExpect(content().string(containsString("htmx.min.js")));
        mvc.perform(get("/css/tokens.css")).andExpect(status().isOk());
        mvc.perform(get("/webjars/htmx.org/dist/htmx.min.js")).andExpect(status().isOk());
        mvc.perform(get("/fonts/hanken-grotesk-400.woff2")).andExpect(status().isOk());
    }

    /**
     * Bug found by the first Playwright run: the favicon's content-hashed URL wasn't public, so the browser's anonymous
     * request for it was saved as "the page you wanted" and the user landed on the icon after logging in.
     */
    @Test
    void theFaviconIsPublicUnderItsVersionedUrlSoLoginNeverLandsOnIt() throws Exception {
        String html = mvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        int start = html.indexOf("href=\"/favicon");
        org.assertj.core.api.Assertions.assertThat(start).isPositive();
        String icon = html.substring(start + "href=\"".length(), html.indexOf('"', start + "href=\"".length()));
        org.assertj.core.api.Assertions.assertThat(icon).matches("/favicon-[0-9a-f]+\\.svg");
        mvc.perform(get(icon)).andExpect(status().isOk());
        mvc.perform(get("/favicon.svg")).andExpect(status().isOk());
    }
}

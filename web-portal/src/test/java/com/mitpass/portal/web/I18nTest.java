package com.mitpass.portal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.mitpass.portal.TestTokens;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.GatewayClient;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * i18n (D15: English and Tamil; T08.11): both bundles carry exactly the same keys, every key a template names exists,
 * the key families built at run time (state chips, roles, lifecycle actions, scan results) are complete, and pages
 * rendered in Tamil have no missing-key markers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class I18nTest {

    static final Pattern TEMPLATE_KEY = Pattern.compile("#\\{([a-zA-Z][a-zA-Z0-9.]*)[}(]");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GatewayClient gateway;

    @BeforeEach
    void catalogue() {
        given(gateway.categories()).willReturn(List.of(new Category(1L, "TECHNICAL", "Technical")));
        given(gateway.departments()).willReturn(List.of(new Department("CSE", "Computer Science")));
        given(gateway.search(any())).willReturn(new Page<>(List.of(PortalPagesTest.summary()), 0, 12, 1, 1));
        given(gateway.events(any(), any(), any(), anyInt()))
                .willReturn(new Page<>(List.of(PortalPagesTest.summary()), 0, 20, 1, 1));
    }

    static Properties bundle(String name) throws IOException {
        Properties p = new Properties();
        try (var in = new InputStreamReader(I18nTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    @Test
    void bothBundlesHaveTheSameKeys() throws IOException {
        Set<String> english = new TreeSet<>(bundle("messages.properties").stringPropertyNames());
        Set<String> tamil = new TreeSet<>(bundle("messages_ta.properties").stringPropertyNames());

        assertThat(tamil).as("keys missing in Tamil").containsAll(english);
        assertThat(english).as("keys only in Tamil").containsAll(tamil);
    }

    /**
     * always-use-message-format: a lone apostrophe starts a quoted section, so "couldn't" shows as "couldnt" and a
     * {0} after it is never filled in (found twice in P7). Every apostrophe is written ''.
     */
    @Test
    void everyApostropheIsDoubled() throws IOException {
        for (String name : List.of("messages.properties", "messages_ta.properties")) {
            Properties bundle = bundle(name);
            List<String> lone = bundle.stringPropertyNames().stream()
                    .filter(key -> bundle.getProperty(key).replace("''", "").contains("'"))
                    .sorted()
                    .toList();
            assertThat(lone).as("keys with a lone apostrophe in " + name).isEmpty();
        }
    }

    @Test
    void everyKeyATemplateNamesExists() throws IOException {
        Properties english = bundle("messages.properties");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                Matcher m = TEMPLATE_KEY.matcher(Files.readString(file));
                while (m.find()) {
                    if (!english.containsKey(m.group(1))) {
                        missing.add(m.group(1) + " (" + file.getFileName() + ")");
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void theKeyFamiliesBuiltAtRunTimeAreComplete() throws IOException {
        Properties english = bundle("messages.properties");
        List<String> states = List.of(
                "DRAFT",
                "PUBLISHED",
                "REGISTRATION_OPEN",
                "REGISTRATION_CLOSED",
                "LIVE",
                "COMPLETED",
                "CANCELLED",
                "PENDING",
                "CONFIRMED",
                "WAITLISTED",
                "ISSUED",
                "ACTIVE",
                "EXPIRED",
                "REVOKED",
                "VALID",
                "NOT_ARRIVED",
                "IN",
                "OUT",
                "UP",
                "DOWN");
        List<String> results = List.of(
                "ACCEPTED",
                "CHECKED_OUT",
                "ALREADY_CHECKED_IN",
                "REVOKED",
                "EXPIRED_TOKEN",
                "WRONG_EVENT",
                "WRONG_GATE",
                "NOT_YET_OPEN",
                "INVALID_SIGNATURE",
                "UNAUTHORIZED_SCANNER",
                "NOT_CHECKED_IN",
                "REENTRY_NOT_ALLOWED",
                "EVENT_CLOSED",
                "UNKNOWN_PASS",
                "INVALID_PASS_NUMBER");
        Set<String> missing = new TreeSet<>();
        states.forEach(s -> check(english, "state." + s, missing));
        AdminPagesController.ROLES.forEach(r -> check(english, "role." + r, missing));
        List.of("PUBLISHED", "REGISTRATION_OPEN", "REGISTRATION_CLOSED", "UNDO_START", "COMPLETED", "CANCELLED")
                .forEach(a -> check(english, "action." + a, missing));
        List.of("CANCELLED", "COMPLETED", "UNDO_START").forEach(a -> check(english, "consequence." + a, missing));
        results.forEach(r -> {
            check(english, "scan.word." + r, missing);
            check(english, "scan.hint." + r, missing);
        });
        assertThat(missing).isEmpty();
    }

    private static void check(Properties bundle, String key, Set<String> missing) {
        if (!bundle.containsKey(key)) {
            missing.add(key);
        }
    }

    @Test
    void pagesInTamilAreTamilWithoutMissingKeys() throws Exception {
        Cookie tamil = new Cookie("mitpass-lang", "ta");
        mvc.perform(get("/login").cookie(tamil))
                .andExpect(content().string(containsString("lang=\"ta\"")))
                .andExpect(content().string(containsString("உள்நுழை")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/events").cookie(tamil))
                .andExpect(content().string(containsString("நிகழ்வுகளைத் தேடு")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/signup").cookie(tamil))
                .andExpect(content().string(containsString("உங்கள் கணக்கை உருவாக்குங்கள்")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/organizer/events")
                        .cookie(tamil)
                        .with(authentication(TestTokens.login(TestTokens.user("ORGANIZER")))))
                .andExpect(content().string(containsString("பதிவை மூடு")))
                .andExpect(content().string(not(containsString("??"))));
    }
}

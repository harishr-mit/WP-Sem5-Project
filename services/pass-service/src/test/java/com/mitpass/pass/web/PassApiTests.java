package com.mitpass.pass.web;

import static com.mitpass.pass.PassFixtures.confirmed;
import static com.mitpass.pass.PassFixtures.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.jayway.jsonpath.JsonPath;
import com.mitpass.common.security.Roles;
import com.mitpass.pass.FakeEventCatalog;
import com.mitpass.pass.PassFixtures;
import com.mitpass.pass.PassFixtures.TestEvent;
import com.mitpass.pass.PassIntegrationTest;
import com.mitpass.pass.TestClock;
import com.mitpass.pass.keys.PassKeyService;
import com.mitpass.pass.messaging.PassInboxListener;
import com.mitpass.pass.repository.PassRepository;
import com.mitpass.security.test.TestJwtIssuer;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.SignedJWT;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The pass API of docs/plans/phase-4.md §7 through MockMvc: media types, problem types, revoke and the feed. */
@PassIntegrationTest
class PassApiTests {

    static final String OWNER = TestJwtIssuer.tokenFor(PassFixtures.ORGANIZER, Roles.ORGANIZER);
    static final String ADMIN = TestJwtIssuer.token(Roles.ADMIN);
    static final String VOLUNTEER = TestJwtIssuer.token(Roles.STUDENT, Roles.VOLUNTEER);

    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    @Autowired
    MockMvc mvc;

    @Autowired
    PassInboxListener listener;

    @Autowired
    PassRepository passes;

    @Autowired
    PassKeyService keys;

    @Autowired
    FakeEventCatalog catalog;

    @Autowired
    TestClock clock;

    UUID student;
    String holder;
    UUID activePass;
    UUID lockedPass;
    TestEvent soon;
    TestEvent later;

    @BeforeEach
    void setUp() {
        clock.set(TestClock.START);
        student = UUID.randomUUID();
        holder = TestJwtIssuer.tokenFor(student, Roles.STUDENT);
        soon = PassFixtures.event(Duration.ofHours(1)); // live code already unlocked
        later = PassFixtures.event(Duration.ofDays(10)); // locked; static QR off
        catalog.put(soon.info(true, "#1D4E89"));
        catalog.put(later.info(false, null));
        activePass = issue(soon, student);
        lockedPass = issue(later, student);
    }

    @Test
    void walletListsMyPasses() throws Exception {
        mvc.perform(as(holder, get("/api/passes/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].status", hasItem("ACTIVE")))
                .andExpect(jsonPath("$.content[*].status", hasItem("ISSUED")));
        mvc.perform(as(holder, get("/api/passes/{id}", lockedPass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passNumber", startsWith(later.code() + "-000001-")))
                .andExpect(jsonPath("$.codeUnlocksAt")
                        .value(later.startsAt().minus(Duration.ofMinutes(150)).toString()));
    }

    @Test
    void liveQrIsAPngThatDecodesToAVerifiableToken() throws Exception {
        byte[] png = mvc.perform(as(holder, get("/api/passes/{id}/qr/live", activePass)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        String token = new QRCodeReader()
                .decode(
                        new BinaryBitmap(new HybridBinarizer(
                                new BufferedImageLuminanceSource(ImageIO.read(new ByteArrayInputStream(png))))),
                        Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE))
                .getText();
        SignedJWT jwt = SignedJWT.parse(token);
        ECKey key = (ECKey) keys.publicJwkSet().getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(jwt.verify(new ECDSAVerifier(key))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringClaim("pid")).isEqualTo(activePass.toString());
    }

    @Test
    void liveTokenAsJsonAndSvgByAcceptOrFormat() throws Exception {
        String json = mvc.perform(
                        as(holder, get("/api/passes/{id}/qr/live", activePass)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("LIVE"))
                .andExpect(jsonPath("$.refreshAfterSeconds").value(30))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(Duration.between(
                        java.time.Instant.parse(JsonPath.read(json, "$.issuedAt")),
                        java.time.Instant.parse(JsonPath.read(json, "$.expiresAt"))))
                .isEqualTo(Duration.ofSeconds(45));

        mvc.perform(as(holder, get("/api/passes/{id}/qr/live", activePass)).accept("image/svg+xml"))
                .andExpect(content().contentType("image/svg+xml"));
        mvc.perform(as(holder, get("/api/passes/{id}/qr/live?format=svg", activePass)))
                .andExpect(content().contentType("image/svg+xml"));
    }

    @Test
    void lockedCodeIsA409ProblemEvenWhenAnImageWasAsked() throws Exception {
        mvc.perform(as(holder, get("/api/passes/{id}/qr/live", lockedPass)).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:code-locked"))
                .andExpect(jsonPath("$.unlocksAt")
                        .value(later.startsAt().minus(Duration.ofMinutes(150)).toString()));
    }

    @Test
    void staticQrFollowsTheEventSwitch() throws Exception {
        mvc.perform(as(holder, get("/api/passes/{id}/qr/static", activePass)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("STATIC"))
                .andExpect(jsonPath("$.expiresAt")
                        .value(soon.endsAt().plus(Duration.ofHours(2)).toString()));
        mvc.perform(as(holder, get("/api/passes/{id}/qr/static", lockedPass)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:static-qr-disabled"));
    }

    @Test
    void ticketPdfIsNamedAfterTheEventTheHolderAndThePassNumber() throws Exception {
        var pass = tx.execute(status -> {
            var p = passes.findById(activePass).orElseThrow();
            return TicketFileName.of(p.getEvent().getTitle(), p.getHolderName(), p.getPassNumber());
        });

        byte[] pdf = mvc.perform(as(holder, get("/api/passes/{id}/ticket.pdf", activePass)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, TicketFileName.header(pass)))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // An event without static QR still gets a ticket, without the code.
        mvc.perform(as(holder, get("/api/passes/{id}/ticket.pdf", lockedPass))).andExpect(status().isOk());
    }

    @Test
    void organizerRevokesThenCodesStopAndTheFeedHasIt() throws Exception {
        long since = feedCursor();

        mvc.perform(as(OWNER, post("/api/passes/{id}/revoke", activePass)).content("{\"note\":\"Duplicate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("ORGANIZER"))
                .andExpect(jsonPath("$.revokeNote").value("Duplicate"));

        mvc.perform(as(holder, get("/api/passes/{id}/qr/live", activePass)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:pass-not-valid"));
        mvc.perform(as(holder, get("/api/passes/{id}/ticket.pdf", activePass))).andExpect(status().isConflict());
        mvc.perform(as(OWNER, post("/api/passes/{id}/revoke", activePass)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mitpass:problem:illegal-transition"));

        mvc.perform(as(VOLUNTEER, get("/api/revocations?since={since}", since)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].passId").value(activePass.toString()))
                .andExpect(jsonPath("$.items[0].reason").value("ORGANIZER"))
                .andExpect(jsonPath("$.nextSince").value(org.hamcrest.Matchers.greaterThan((int) since)));
        mvc.perform(as(OWNER, get("/api/passes/{id}/history", activePass)))
                .andExpect(jsonPath("$[*].toStatus", org.hamcrest.Matchers.contains("ISSUED", "ACTIVE", "REVOKED")));
    }

    @Test
    void organizerListsTheEventsPasses() throws Exception {
        mvc.perform(as(OWNER, get("/api/passes?eventId={id}", soon.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(activePass.toString()));
        mvc.perform(as(OWNER, get("/api/passes"))).andExpect(status().isBadRequest());
    }

    @Test
    void passKeysArePublicAndAdminCanRotate() throws Exception {
        String before = keys.activeKey().getKeyID();
        mvc.perform(get("/.well-known/pass-jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].crv").value("P-256"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());

        mvc.perform(as(ADMIN, post("/api/passes/keys/rotate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kid").value(org.hamcrest.Matchers.not(before)));
        mvc.perform(get("/.well-known/pass-jwks.json")).andExpect(jsonPath("$.keys[*].kid", hasItem(before)));
    }

    private long feedCursor() throws Exception {
        String body = mvc.perform(as(ADMIN, get("/api/revocations?since=0&limit=1000")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.nextSince")).longValue();
    }

    private UUID issue(TestEvent event, UUID user) {
        UUID registration = UUID.randomUUID();
        listener.onMessage(message(confirmed(event, registration, user)));
        return passes.findByRegistrationId(registration).orElseThrow().getId();
    }

    static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }
}

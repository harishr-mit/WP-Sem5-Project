package com.mitpass.portal.client;

import com.mitpass.portal.client.ApiModels.Assignment;
import com.mitpass.portal.client.ApiModels.AttendanceSummary;
import com.mitpass.portal.client.ApiModels.AttendeeRow;
import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.CertificateRow;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.Eligibility;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.EventQuery;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiModels.FieldError;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.ApiModels.InstanceHealth;
import com.mitpass.portal.client.ApiModels.KeyRotation;
import com.mitpass.portal.client.ApiModels.LookupAnswer;
import com.mitpass.portal.client.ApiModels.MyAttendance;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.PassLookup;
import com.mitpass.portal.client.ApiModels.RegisterResult;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiModels.ScanAnswer;
import com.mitpass.portal.client.ApiModels.SyncResult;
import com.mitpass.portal.client.ApiModels.TokenResponse;
import com.mitpass.portal.client.ApiModels.UserProfile;
import com.mitpass.portal.client.ApiModels.Venue;
import com.mitpass.portal.client.ApiModels.Volunteer;
import com.mitpass.portal.config.PortalProperties;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every call the portal makes, all through the gateway (docs/DECISIONS.md D9). The logged-in user's access token is
 * added server side from the session; the browser never sees it. Error responses come back as
 * {@link ApiProblemException} (the service's ProblemDetail) or, for a rejected token, {@link SessionExpiredException}.
 */
@Component
public class GatewayClient {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final RestClient http;
    private final JsonMapper json;
    private final AccessTokenSource tokens;

    @Autowired
    public GatewayClient(
            RestClient.Builder builder, PortalProperties properties, JsonMapper json, AccessTokenSource tokens) {
        this(builder.clone().requestFactory(timeouts(properties)), properties.gatewayUrl(), json, tokens);
    }

    /** For tests: a builder already bound to a mock server keeps its request factory; tokens are never refreshed. */
    GatewayClient(RestClient.Builder configured, String gatewayUrl, JsonMapper json) {
        this(configured, gatewayUrl, json, AccessTokenSource.fromSecurityContext());
    }

    GatewayClient(RestClient.Builder configured, String gatewayUrl, JsonMapper json, AccessTokenSource tokens) {
        this.json = json;
        this.tokens = tokens;
        this.http = configured
                .baseUrl(gatewayUrl)
                .requestInterceptor(this::addTokenAndCorrelationId)
                .defaultStatusHandler(HttpStatusCode::isError, this::raise)
                .build();
    }

    private static SimpleClientHttpRequestFactory timeouts(PortalProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeouts().connect());
        factory.setReadTimeout(properties.timeouts().read());
        return factory;
    }

    // ---- auth-service --------------------------------------------------------------------------------------------

    /** Sends the browser's IP along: every login comes from the portal, so the gateway limits by this one (D17). */
    public TokenResponse login(String email, String password) {
        String browserIp = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes page
                ? page.getRequest().getRemoteAddr()
                : null;
        return http.post()
                .uri("/api/auth/login")
                .headers(h -> {
                    if (browserIp != null) {
                        h.set("X-Forwarded-For", browserIp);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(TokenResponse.class);
    }

    public void logout(String refreshToken) {
        http.post()
                .uri("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("refreshToken", refreshToken))
                .retrieve()
                .toBodilessEntity();
    }

    /** Field names match auth-service's SignupRequest, so its 400 field errors map straight onto the form. */
    public void signup(String fullName, String email, String password, String department, Integer year, String regNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("password", password);
        body.put("department", department);
        body.put("year", year);
        body.put("registerNumber", regNo == null || regNo.isBlank() ? null : regNo.trim());
        http.post()
                .uri("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /** Confirms the address with the token from the email link (D9 row: the link opens the portal, not the API). */
    public void verifyEmail(String token) {
        http.post()
                .uri("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", token))
                .retrieve()
                .toBodilessEntity();
    }

    /** Asks auth-service to send a new verification link; it answers the same whether or not the address exists. */
    public void resendVerification(String email) {
        http.post()
                .uri("/api/auth/verify-email/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email))
                .retrieve()
                .toBodilessEntity();
    }

    public List<Department> departments() {
        return http.get().uri("/api/departments").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    // ---- event-service: catalogue and lifecycle ------------------------------------------------------------------

    /**
     * Events for the organizer's list and the scanner's gate picker: up to 100 (the API's page limit), so an organizer
     * with more than a screenful still sees all of them (found in the Phase 6 sweep: it stopped at 20).
     */
    public Page<EventSummary> events(String q, String category, UUID organizerId, int page) {
        return http.get()
                .uri(uri -> {
                    UriBuilder b =
                            uri.path("/api/events").queryParam("page", page).queryParam("size", 100);
                    if (q != null && !q.isBlank()) {
                        b.queryParam("q", q);
                    }
                    if (category != null && !category.isBlank()) {
                        b.queryParam("category", category);
                    }
                    if (organizerId != null) {
                        b.queryParam("organizerId", organizerId);
                    }
                    return b.queryParam("sort", "startsAt").build();
                })
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    /** The student catalogue: text, category, department and a date range, 12 events a page. */
    public Page<EventSummary> search(EventQuery query) {
        return http.get()
                .uri(uri -> {
                    UriBuilder b = uri.path("/api/events")
                            .queryParam("page", Math.max(0, query.page()))
                            .queryParam("size", 12)
                            .queryParam("sort", "startsAt");
                    if (query.q() != null && !query.q().isBlank()) {
                        b.queryParam("q", query.q().strip());
                    }
                    if (query.category() != null && !query.category().isBlank()) {
                        b.queryParam("category", query.category());
                    }
                    if (query.department() != null && !query.department().isBlank()) {
                        b.queryParam("department", query.department());
                    }
                    if (query.from() != null) {
                        b.queryParam("from", query.from());
                    }
                    if (query.to() != null) {
                        b.queryParam("to", query.to());
                    }
                    return b.build();
                })
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public EventDetail event(long id) {
        return http.get().uri("/api/events/{id}", id).retrieve().body(EventDetail.class);
    }

    /** Venues for the event form (event-service; a short list, one page). */
    public List<Venue> venues() {
        Page<Venue> page =
                http.get().uri("/api/venues?size=100&sort=name").retrieve().body(new ParameterizedTypeReference<>() {});
        return page == null ? List.of() : page.content();
    }

    /** {@code POST /api/events} with its first sessions, in one transaction; the event starts as DRAFT. */
    public EventDetail createEvent(Map<String, Object> body) {
        return http.post()
                .uri("/api/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(EventDetail.class);
    }

    /** {@code PUT /api/events/{id}} with the version read before editing (409 if someone changed it meanwhile). */
    public EventDetail updateEvent(long id, Map<String, Object> body) {
        return http.put()
                .uri("/api/events/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(EventDetail.class);
    }

    public void addSession(long eventId, Map<String, Object> body) {
        http.post()
                .uri("/api/events/{id}/sessions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public void updateSession(long eventId, long sessionId, Map<String, Object> body) {
        http.put()
                .uri("/api/events/{id}/sessions/{sid}", eventId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteSession(long eventId, long sessionId) {
        http.delete()
                .uri("/api/events/{id}/sessions/{sid}", eventId, sessionId)
                .retrieve()
                .toBodilessEntity();
    }

    public List<Category> categories() {
        return http.get().uri("/api/categories").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public EventDetail transition(long eventId, String to) {
        return http.post()
                .uri("/api/events/{id}/transitions", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("to", to, "reason", "Changed in the web portal"))
                .retrieve()
                .body(EventDetail.class);
    }

    // ---- event-service: registrations ----------------------------------------------------------------------------

    public Eligibility eligibility(long eventId) {
        return http.get()
                .uri("/api/events/{id}/eligibility", eventId)
                .retrieve()
                .body(Eligibility.class);
    }

    public RegisterResult register(long eventId, String idempotencyKey) {
        ResponseEntity<Registration> response = http.post()
                .uri("/api/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .body(Map.of("eventId", eventId))
                .retrieve()
                .toEntity(Registration.class);
        return new RegisterResult(
                response.getBody(), "true".equals(response.getHeaders().getFirst(REPLAYED)));
    }

    public Page<Registration> myRegistrations() {
        return http.get().uri("/api/registrations/me?size=50").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public Registration cancel(UUID registrationId) {
        return http.post()
                .uri("/api/registrations/{id}/cancel", registrationId)
                .retrieve()
                .body(Registration.class);
    }

    // ---- pass-service (Phase 4) -----------------------------------------------------------------------------------

    public Page<Pass> myPasses() {
        return http.get().uri("/api/passes/me?size=50").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public Pass pass(UUID id) {
        return http.get().uri("/api/passes/{id}", id).retrieve().body(Pass.class);
    }

    public Page<Pass> eventPasses(long eventId) {
        return http.get()
                .uri("/api/passes?eventId={id}&size=100", eventId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Pass revokePass(UUID id, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("note", note == null || note.isBlank() ? null : note.trim());
        return http.post()
                .uri("/api/passes/{id}/revoke", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Pass.class);
    }

    /** RL12: the event's organizer or an admin voids a pass's wrong check-ins (all sessions); the reason is audited. */
    public void voidCheckIn(long eventId, UUID passId, String reason) {
        http.post()
                .uri("/api/attendance/events/{id}/void", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("passId", passId, "reason", reason))
                .retrieve()
                .toBodilessEntity();
    }

    /** ADMIN (RL10): a new pass for a revoked pass's registration; the revoked one stays revoked. */
    public Pass reissuePass(UUID id) {
        return http.post().uri("/api/passes/{id}/reissue", id).retrieve().body(Pass.class);
    }

    /** The live QR as a PNG (a fresh 45 s token each time); the token itself stays inside the image. */
    public Binary liveQrPng(UUID passId) {
        return binary("/api/passes/{id}/qr/live", passId, MediaType.IMAGE_PNG);
    }

    /** The same live QR as SVG: crisp at any size on the pass card (RL6 cause b). */
    public Binary liveQrSvg(UUID passId) {
        return binary("/api/passes/{id}/qr/live?format=svg", passId, MediaType.parseMediaType("image/svg+xml"));
    }

    public Binary ticketPdf(UUID passId) {
        return binary("/api/passes/{id}/ticket.pdf", passId, MediaType.APPLICATION_PDF);
    }

    /** "Add to calendar" (P7, D16): notification-service's public invite for the event. */
    public Binary calendar(long eventId) {
        return binary(
                "/api/notifications/events/{id}/calendar.ics", eventId, MediaType.parseMediaType("text/calendar"));
    }

    // ---- event-service: an event's registrations (organizer) -------------------------------------------------------

    public Page<Registration> eventRegistrations(long eventId) {
        return http.get()
                .uri("/api/events/{id}/registrations?size=100", eventId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    // ---- Phase 5: staffing (event-service) and scans / attendance (checkin-service) ---------------------------------

    public List<Assignment> myAssignments() {
        return http.get().uri("/api/volunteers/me/assignments").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public Gate addGate(long eventId, String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name == null || name.isBlank() ? null : name.strip());
        return http.post()
                .uri("/api/events/{id}/gates", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Gate.class);
    }

    public void removeGate(long eventId, long gateId) {
        http.delete()
                .uri("/api/events/{id}/gates/{gid}", eventId, gateId)
                .retrieve()
                .toBodilessEntity();
    }

    public List<Volunteer> eventVolunteers(long eventId) {
        return http.get()
                .uri("/api/events/{id}/volunteers", eventId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    /** Assigns a volunteer by email to one gate, or to every gate of the event ({@code gateId} null). */
    public Volunteer assignVolunteer(long eventId, String email, Long gateId) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("gateId", gateId);
        return http.post()
                .uri("/api/events/{id}/volunteers", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Volunteer.class);
    }

    public void unassignVolunteer(long eventId, UUID volunteerId) {
        http.delete()
                .uri("/api/events/{id}/volunteers/{vid}", eventId, volunteerId)
                .retrieve()
                .toBodilessEntity();
    }

    public List<Gate> gates(long eventId) {
        return http.get().uri("/api/events/{id}/gates", eventId).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    /**
     * Sends a scan. A refusal (409, 422, 404, or the scanner 403) is not an error here: its ProblemDetail carries the
     * same fields as an accepted scan, so both come back as a {@link ScanAnswer}. Anything else raises as usual.
     */
    public ScanAnswer scan(String token, String passNumber, long gateId, String deviceId, String mode) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("passNumber", passNumber);
        body.put("gateId", gateId);
        body.put("deviceId", deviceId);
        body.put("mode", mode);
        return http.post()
                .uri("/api/scans")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> scanAnswer(request, response, ScanAnswer.class));
    }

    // ---- offline scanner (P7): passed through as JSON, the browser keeps them ------------------------------------

    /** pass-service's public keys (the same set checkin-service verifies with). */
    public String passJwks() {
        return http.get().uri("/.well-known/pass-jwks.json").retrieve().body(String.class);
    }

    /** checkin-service's offline manifest for one event: sessions, time rules, passes with status, first names. */
    public String scanManifest(long eventId) {
        return http.get()
                .uri(uri -> uri.path("/api/scans/manifest")
                        .queryParam("eventId", eventId)
                        .build())
                .retrieve()
                .body(String.class);
    }

    /** Queued offline scans, as the phone sent them; one answer each. */
    public String scanBatch(String body) {
        return http.post()
                .uri("/api/scans/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** Manual entry, step one: the holder's name for the visual check, or the refusal. */
    public LookupAnswer lookup(String passNumber, long gateId, String deviceId) {
        return http.get()
                .uri(uri -> uri.path("/api/scans/lookup")
                        .queryParam("passNumber", passNumber)
                        .queryParam("gateId", gateId)
                        .queryParam("deviceId", deviceId)
                        .build())
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return new LookupAnswer(
                                json.readValue(response.getBody().readAllBytes(), PassLookup.class), null);
                    }
                    return new LookupAnswer(null, scanAnswer(request, response, ScanAnswer.class));
                });
    }

    /** The live dashboard's first paint, and its fallback while the socket is down (P7). */
    public ApiModels.LiveSnapshot liveSnapshot(long eventId) {
        return http.get()
                .uri("/api/attendance/events/{id}/live", eventId)
                .retrieve()
                .body(ApiModels.LiveSnapshot.class);
    }

    public AttendanceSummary attendanceSummary(long eventId) {
        return http.get()
                .uri("/api/attendance/events/{id}/summary", eventId)
                .retrieve()
                .body(AttendanceSummary.class);
    }

    public Page<AttendeeRow> attendees(long eventId) {
        return http.get()
                .uri("/api/attendance/events/{id}/attendees?size=100", eventId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public List<CertificateRow> certificates(long eventId) {
        return http.get()
                .uri("/api/attendance/events/{id}/certificates", eventId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Binary attendanceCsv(long eventId) {
        return binary("/api/attendance/events/{id}/export.csv", eventId, MediaType.parseMediaType("text/csv"));
    }

    /** The student's own attendance record and certificate progress ("Stamps"). */
    public List<MyAttendance> myAttendance() {
        return http.get().uri("/api/attendance/me").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public UserProfile me() {
        return http.get().uri("/api/users/me").retrieve().body(UserProfile.class);
    }

    /** Ends every login of this user (all refresh-token families, D7); issued access tokens run out within 15 min. */
    public void logoutEverywhere() {
        http.delete().uri("/api/users/me/sessions").retrieve().toBodilessEntity();
    }

    // ---- admin (Phase 6) ----------------------------------------------------------------------------------------

    /** auth-service's user search (ADMIN): {@code q} matches email, name or register number. */
    public Page<UserProfile> users(String q, String role, int page) {
        return http.get()
                .uri(uri -> {
                    UriBuilder b = uri.path("/api/users")
                            .queryParam("page", Math.max(0, page))
                            .queryParam("size", 20);
                    if (q != null && !q.isBlank()) {
                        b.queryParam("q", q.strip());
                    }
                    if (role != null && !role.isBlank()) {
                        b.queryParam("role", role);
                    }
                    return b.build();
                })
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public UserProfile user(UUID id) {
        return http.get().uri("/api/users/{id}", id).retrieve().body(UserProfile.class);
    }

    /** Replaces the roles (ORGANIZER brings VOLUNTEER, RL11); auth-service also ends the user's sessions. */
    public UserProfile setRoles(UUID id, java.util.Collection<String> roles) {
        return http.put()
                .uri("/api/users/{id}/roles", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("roles", roles))
                .retrieve()
                .body(UserProfile.class);
    }

    public void unlock(UUID id) {
        http.post().uri("/api/users/{id}/unlock", id).retrieve().toBodilessEntity();
    }

    /** RL10: ban or lift a ban (ADMIN). */
    public UserProfile setBanned(UUID id, boolean banned) {
        return http.post()
                .uri(banned ? "/api/users/{id}/ban" : "/api/users/{id}/unban", id)
                .retrieve()
                .body(UserProfile.class);
    }

    public void endSessions(UUID id) {
        http.delete().uri("/api/users/{id}/sessions", id).retrieve().toBodilessEntity();
    }

    public KeyRotation rotateAuthKey() {
        return http.post().uri("/api/auth/keys/rotate").retrieve().body(KeyRotation.class);
    }

    public KeyRotation rotatePassKey() {
        return http.post().uri("/api/passes/keys/rotate").retrieve().body(KeyRotation.class);
    }

    /** The gateway's own health overview (D15): every registered instance. */
    // ---- dead letters (P7, D13): each consumer's tooling, routed by the gateway under /api/admin/dead-letters ------

    public ApiModels.DeadLetterOverview deadLetters(String service) {
        return http.get()
                .uri("/api/admin/dead-letters/{service}?limit=50", service)
                .retrieve()
                .body(ApiModels.DeadLetterOverview.class);
    }

    public void replayDeadLetter(String service, String messageId) {
        http.post()
                .uri("/api/admin/dead-letters/{service}/{id}/replay", service, messageId)
                .retrieve()
                .toBodilessEntity();
    }

    public int replayAllDeadLetters(String service) {
        Map<?, ?> body = http.post()
                .uri("/api/admin/dead-letters/{service}/replay-all", service)
                .retrieve()
                .body(Map.class);
        return body == null || !(body.get("replayed") instanceof Number n) ? 0 : n.intValue();
    }

    public void discardDeadLetter(String service, String messageId) {
        http.delete()
                .uri("/api/admin/dead-letters/{service}/{id}", service, messageId)
                .retrieve()
                .toBodilessEntity();
    }

    public List<InstanceHealth> adminHealth() {
        return http.get().uri("/api/admin/health").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public SyncResult syncAttendance(long eventId) {
        return http.post()
                .uri("/api/attendance/events/{id}/sync", eventId)
                .retrieve()
                .body(SyncResult.class);
    }

    /** 2xx, or a refusal whose body has a {@code result}: read as {@code type}. Otherwise the usual error handling. */
    private <T> T scanAnswer(HttpRequest request, ClientHttpResponse response, Class<T> type) throws IOException {
        byte[] body = response.getBody().readAllBytes();
        int status = response.getStatusCode().value();
        boolean refusal = status == 403 || status == 404 || status == 409 || status == 422;
        if (response.getStatusCode().is2xxSuccessful()
                || (refusal && new String(body, java.nio.charset.StandardCharsets.UTF_8).contains("\"result\""))) {
            return json.readValue(body, type);
        }
        raise(request, status, body);
        throw new IllegalStateException("unreachable");
    }

    private Binary binary(String path, Object id, MediaType accept) {
        ResponseEntity<byte[]> response =
                http.get().uri(path, id).accept(accept).retrieve().toEntity(byte[].class);
        MediaType type = response.getHeaders().getContentType();
        return new Binary(
                response.getBody(),
                type == null ? accept.toString() : type.toString(),
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    // ---- plumbing --------------------------------------------------------------------------------------------------

    /**
     * Adds the session's access token (refreshed first when it is about to expire, docs/plans/phase-6.md §7) and the
     * correlation id. A 401 to a token we sent gets one refresh and one retry; a second 401 means the session is over
     * ({@link #raise}).
     */
    private ClientHttpResponse addTokenAndCorrelationId(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        String token = tokens.current();
        if (token != null) {
            request.getHeaders().setBearerAuth(token);
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            request.getHeaders().set(CORRELATION_HEADER, correlationId);
        }
        ClientHttpResponse response = execution.execute(request, body);
        if (token != null && response.getStatusCode().value() == 401) {
            String fresh = tokens.afterRejection(token);
            if (fresh != null && !fresh.equals(token)) {
                response.close();
                request.getHeaders().setBearerAuth(fresh);
                return execution.execute(request, body);
            }
        }
        return response;
    }

    record Problem(
            String type, String title, Integer status, String detail, List<FieldError> errors, List<String> reasons) {}

    private void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        raise(request, response.getStatusCode().value(), response.getBody().readAllBytes());
    }

    private void raise(HttpRequest request, int status, byte[] body) {
        if (status == 401 && request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
            throw new SessionExpiredException();
        }
        Problem problem;
        try {
            problem = body.length == 0 ? null : json.readValue(body, Problem.class);
        } catch (RuntimeException notAProblem) {
            problem = null;
        }
        if (problem == null) {
            throw new ApiProblemException(status, null, null, List.of(), List.of());
        }
        throw new ApiProblemException(status, problem.type(), problem.detail(), problem.errors(), problem.reasons());
    }
}

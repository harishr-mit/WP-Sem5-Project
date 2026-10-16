package com.mitpass.portal.client;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.client.ApiModels.Assignment;
import com.mitpass.portal.client.ApiModels.AttendanceSummary;
import com.mitpass.portal.client.ApiModels.AttendeeRow;
import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.Category;
import com.mitpass.portal.client.ApiModels.CertificateRow;
import com.mitpass.portal.client.ApiModels.Department;
import com.mitpass.portal.client.ApiModels.Eligibility;
import com.mitpass.portal.client.ApiModels.EventDetail;
import com.mitpass.portal.client.ApiModels.EventSummary;
import com.mitpass.portal.client.ApiModels.FieldError;
import com.mitpass.portal.client.ApiModels.Gate;
import com.mitpass.portal.client.ApiModels.LookupAnswer;
import com.mitpass.portal.client.ApiModels.Page;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiModels.PassLookup;
import com.mitpass.portal.client.ApiModels.RegisterResult;
import com.mitpass.portal.client.ApiModels.Registration;
import com.mitpass.portal.client.ApiModels.ScanAnswer;
import com.mitpass.portal.client.ApiModels.SyncResult;
import com.mitpass.portal.client.ApiModels.TokenResponse;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
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

    @Autowired
    public GatewayClient(RestClient.Builder builder, PortalProperties properties, JsonMapper json) {
        this(builder.requestFactory(timeouts(properties)), properties.gatewayUrl(), json);
    }

    /** For tests: a builder already bound to a mock server keeps its request factory. */
    GatewayClient(RestClient.Builder configured, String gatewayUrl, JsonMapper json) {
        this.json = json;
        this.http = configured
                .baseUrl(gatewayUrl)
                .requestInterceptor(GatewayClient::addTokenAndCorrelationId)
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

    public TokenResponse login(String email, String password) {
        return http.post()
                .uri("/api/auth/login")
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

    public List<Department> departments() {
        return http.get().uri("/api/departments").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    // ---- event-service: catalogue and lifecycle ------------------------------------------------------------------

    public Page<EventSummary> events(String q, String category, UUID organizerId, int page) {
        return http.get()
                .uri(uri -> {
                    UriBuilder b =
                            uri.path("/api/events").queryParam("page", page).queryParam("size", 20);
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

    public EventDetail event(long id) {
        return http.get().uri("/api/events/{id}", id).retrieve().body(EventDetail.class);
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

    /** The live QR as a PNG (a fresh 45 s token each time); the token itself stays inside the image. */
    public Binary liveQrPng(UUID passId) {
        return binary("/api/passes/{id}/qr/live", passId, MediaType.IMAGE_PNG);
    }

    public Binary ticketPdf(UUID passId) {
        return binary("/api/passes/{id}/ticket.pdf", passId, MediaType.APPLICATION_PDF);
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

    private static ClientHttpResponse addTokenAndCorrelationId(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PortalUser user) {
            request.getHeaders().setBearerAuth(user.accessToken());
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            request.getHeaders().set(CORRELATION_HEADER, correlationId);
        }
        return execution.execute(request, body);
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

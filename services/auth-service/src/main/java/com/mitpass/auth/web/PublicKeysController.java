package com.mitpass.auth.web;

import com.mitpass.auth.keys.SigningKeyService;
import com.mitpass.auth.repository.DepartmentRepository;
import com.mitpass.auth.web.dto.AuthResponses.DepartmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public reference endpoints: the JWK set every service verifies tokens with, and the department list. */
@RestController
@Tag(name = "Public", description = "No token needed")
public class PublicKeysController {

    private final SigningKeyService signingKeys;
    private final DepartmentRepository departments;

    public PublicKeysController(SigningKeyService signingKeys, DepartmentRepository departments) {
        this.signingKeys = signingKeys;
        this.departments = departments;
    }

    @GetMapping("/.well-known/jwks.json")
    @SecurityRequirements
    @Operation(
            summary = "Public signing keys (JWK set)",
            description = "Services verify access tokens against these keys, picking one by the token's `kid`.")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(signingKeys.publicJwkSet().toJSONObject(true));
    }

    @GetMapping("/api/departments")
    @SecurityRequirements
    @Operation(summary = "Departments a user can belong to (for the sign-up form)")
    public List<DepartmentResponse> departments() {
        return departments.findAll(Sort.by("code")).stream()
                .map(DepartmentResponse::from)
                .toList();
    }
}

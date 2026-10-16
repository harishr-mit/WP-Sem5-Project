package com.mitpass.pass.web;

import com.mitpass.pass.keys.PassKeyService;
import com.mitpass.pass.web.dto.PassDtos.KeyRotationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Pass keys", description = "Public keys that verify pass QR codes")
public class PassKeysController {

    private final PassKeyService keys;

    public PassKeysController(PassKeyService keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/pass-jwks.json")
    @SecurityRequirements
    @Operation(
            summary = "Public pass keys (JWK set, ES256)",
            description = "Scanners verify a QR's JWS against the key with its `kid`. Retired keys stay listed until "
                    + "every token they signed has expired.")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicJwkSet().toJSONObject(true));
    }

    @PostMapping("/api/passes/keys/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rotate the pass signing key (ADMIN)")
    public KeyRotationResponse rotate() {
        return new KeyRotationResponse(keys.rotate());
    }
}

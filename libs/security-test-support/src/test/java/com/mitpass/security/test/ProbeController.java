package com.mitpass.security.test;

import com.mitpass.common.security.CurrentUser;
import com.mitpass.common.security.web.UserMdcFilter;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints with each kind of protection, for {@link ResourceServerAutoConfigurationTest}. */
@RestController
class ProbeController {

    @GetMapping("/public/ping")
    String ping() {
        return "pong";
    }

    @GetMapping("/api/private")
    String privateEndpoint() {
        return "ok";
    }

    @GetMapping("/api/admin")
    @PreAuthorize("hasRole('ADMIN')")
    String adminOnly() {
        return "admin";
    }

    @GetMapping("/api/me")
    Map<String, Object> me(CurrentUser me) {
        return Map.of("id", me.id().toString(), "roles", me.roles(), "mdcUserId", MDC.get(UserMdcFilter.MDC_KEY));
    }
}

package com.mitpass.event.config;

import com.mitpass.common.security.CurrentUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static {
        // CurrentUser comes from the access token, not from the request: keep it out of the documented parameters.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }

    @Bean
    OpenAPI eventServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("MITPass event-service").version("v1").description("""
                                Event catalogue: events, their sessions, venues and categories.
                                Errors are RFC 9457 `application/problem+json` with `type`, `title`, `status`, \
                                `detail`, `instance`, `correlationId` and, for validation failures, `errors[]`.
                                Times are ISO-8601 instants (UTC); search dates are days in Asia/Kolkata.
                                Reading the catalogue needs no token. Writes need an access token from                                 auth-service (`POST /api/auth/login`, then **Authorize**): ORGANIZER or ADMIN to                                 create, and only the event's own organizer (or an ADMIN) to change it.
                                """))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}

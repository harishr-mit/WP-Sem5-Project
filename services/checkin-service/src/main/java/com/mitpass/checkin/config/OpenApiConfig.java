package com.mitpass.checkin.config;

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
        // CurrentUser comes from the access token, not from the request.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }

    @Bean
    OpenAPI checkinServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("MITPass checkin-service").version("v1").description("""
                                Gate scans and attendance. A VOLUNTEER posts what the attendee shows (a QR token or a \
                                typed pass number) for a gate they are assigned to, and gets a typed result: \
                                `200` for ACCEPTED / CHECKED_OUT, otherwise an `application/problem+json` whose \
                                `result`, `message` and `color` say what to do (§5 of docs/plans/phase-5.md).
                                Organizers read attendance per event, session and gate, export CSV and see who \
                                qualifies for a certificate.
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

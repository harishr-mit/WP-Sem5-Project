package com.mitpass.pass.config;

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
    OpenAPI passServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("MITPass pass-service").version("v1").description("""
                                Passes for confirmed registrations. A pass is issued automatically when \
                                event-service confirms a registration (RabbitMQ), so there is no create endpoint.
                                QR codes carry a compact **ES256 JWS** that anyone can verify against \
                                `/.well-known/pass-jwks.json`. The live code is valid for 45 s and unlocks 2 h \
                                before doors open; the static code (PDF ticket) lasts until the event ends.
                                QR images, tokens and the PDF are for the pass holder only. The event's organizer \
                                and admins list and revoke passes. Errors are `application/problem+json`.
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

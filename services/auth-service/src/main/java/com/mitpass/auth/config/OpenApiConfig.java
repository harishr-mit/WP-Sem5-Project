package com.mitpass.auth.config;

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
    OpenAPI authServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("MITPass auth-service").version("v1").description("""
                                Sign-up, email verification, login, token refresh and user administration.
                                Log in with `POST /api/auth/login`, then press **Authorize** and paste the \
                                `accessToken`. Demo accounts (password `Passw0rd!`): admin@mitpass.test, \
                                organizer.a@mitpass.test, organizer.b@mitpass.test, volunteer@mitpass.test, \
                                2026509001@student.annauniv.edu.
                                Errors are RFC 9457 `application/problem+json` with `correlationId`.
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

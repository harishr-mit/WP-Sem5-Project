package com.mitpass.event.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI eventServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("MITPass event-service").version("v1").description("""
                                Event catalogue: events, their sessions, venues and categories.
                                Errors are RFC 9457 `application/problem+json` with `type`, `title`, `status`, \
                                `detail`, `instance`, `correlationId` and, for validation failures, `errors[]`.
                                Times are ISO-8601 instants (UTC); search dates are days in Asia/Kolkata.
                                """));
    }
}

package com.ticketmaster.user.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Document metadata only — see auth-service's OpenApiConfig for the full
 * rationale (ADR-034: generated, never hand-written; relative server URL
 * since clients only ever reach this service through api-gateway).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI userServiceOpenApi(@Value("${spring.application.name}") String service) {
        return new OpenAPI().servers(List.of(new Server().url("/"))).info(new Info()
                .title(service)
                .version("v1")
                .description("""
                        User profile, preferences and saved payment-method
                        references.

                        Reached through api-gateway at the same paths shown
                        here — the gateway does not rewrite /api/v1."""));
    }
}

package com.ticketmaster.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Document metadata only. Everything else in the spec — paths, schemas,
 * validation constraints, status codes — is derived by springdoc from the
 * controllers and records themselves (ADR-034: generated, never
 * hand-written).
 */
@Configuration
public class OpenApiConfig {

    /**
     * The spec version tracks the REST edge version from ADR-034
     * (/api/v1), not the Gradle artifact version. A patch release of the
     * service must not look like a contract change to anyone diffing the
     * committed spec.
     */
    @Bean
    public OpenAPI authServiceOpenApi(@Value("${spring.application.name}") String service) {
        // Pinned to a relative "/" instead of letting springdoc infer an
        // absolute URL from the incoming request. Inferred means the
        // committed spec would carry http://localhost:<random port> from
        // whatever generated it, and the ADR-034 drift diff would fire on
        // every run for a reason that is not a contract change. Relative
        // is also the honest answer: clients reach this through the
        // gateway, whose host this service does not know.
        return new OpenAPI().servers(List.of(new Server().url("/"))).info(new Info()
                .title(service)
                .version("v1")
                .description("""
                        Identity and token issuance (ADR-012).

                        Reached through api-gateway at the same paths shown
                        here — the gateway does not rewrite /api/v1."""));
    }
}

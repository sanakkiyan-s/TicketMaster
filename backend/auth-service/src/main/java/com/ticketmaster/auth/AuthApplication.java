package com.ticketmaster.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * First service entry point in this repository (ADR-036 Phase 1).
 *
 * Implemented so far: registration, login, the refresh endpoint with reuse
 * detection, the generated OpenAPI surface, RS256 access-token minting, the
 * JWKS endpoint, ADR-010's Vault-backed key source, ADR-012's four-phase key
 * rotation (Vault key source only), and revocation via the ADR-007
 * transactional outbox (logout, logout-everywhere, admin ban).
 *
 * Still absent, named so nobody assumes otherwise: the auth.revocation
 * Kafka Connect/Debezium connector registration (infra, not application
 * code - the outbox row lands correctly, but nothing yet ships it to
 * Kafka in this environment) and api-gateway's consumer of that topic.
 *
 * @EnableScheduling is load-bearing rather than incidental, same as
 * GatewayApplication's own JWKS-cache-refresh timer: RotationScheduler
 * advances ADR-012's rotation state machine on an interval instead of on
 * the request path.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}

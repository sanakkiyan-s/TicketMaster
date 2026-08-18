package com.ticketmaster.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * First service entry point in this repository (ADR-036 Phase 1).
 *
 * Implemented so far: registration, the generated OpenAPI surface, RS256
 * access-token minting and the JWKS endpoint.
 *
 * Still absent, named so nobody assumes otherwise: login itself, the refresh
 * endpoint with reuse detection, ADR-012's four-phase key rotation, ADR-010's
 * Vault-backed key source (today's provider generates an ephemeral in-memory
 * key), and the auth.revocation Kafka producer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}

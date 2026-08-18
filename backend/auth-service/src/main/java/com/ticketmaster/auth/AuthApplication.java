package com.ticketmaster.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * First service entry point in this repository (ADR-036 Phase 1).
 *
 * Scope of this slice is deliberately narrow: the application starts,
 * connects to Postgres, runs its Flyway baseline and answers
 * /actuator/health. JWT issuance, key rotation (ADR-012's four-phase
 * JWKS overlap) and the auth.revocation Kafka producer are separate
 * slices and are not implemented here.
 */
@SpringBootApplication
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}

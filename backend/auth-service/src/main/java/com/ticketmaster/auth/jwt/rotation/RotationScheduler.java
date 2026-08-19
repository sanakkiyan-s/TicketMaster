package com.ticketmaster.auth.jwt.rotation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically advances the rotation state machine (ADR-012). Always
 * registered - see {@link RotationOrchestrator}'s javadoc for why an
 * ephemeral key source does not need this disabled, since {@link
 * RotationOrchestrator#advanceIfDue} is a no-op while IDLE and nothing can
 * ever leave IDLE without Vault.
 *
 * Requires {@code @EnableScheduling} on {@code AuthApplication}, the same
 * requirement {@code GatewayApplication} already documents for its own
 * JWKS-cache-refresh timer.
 */
@Component
class RotationScheduler {

    private final RotationOrchestrator orchestrator;
    private final RotationProperties properties;

    RotationScheduler(RotationOrchestrator orchestrator, RotationProperties properties) {
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${auth.jwt.rotation.scheduler-interval:PT2M}")
    void tick() {
        orchestrator.advanceIfDue(properties);
    }
}

package com.ticketmaster.auth.jwt.rotation;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * ADR-012's rotation durations, validated at startup. Configurable
 * (LoginSecurityProperties/RateLimitProperties' established shape in this
 * codebase) so tests can use durations of a few milliseconds instead of
 * waiting on real minutes, while production keeps the ADR's own numbers as
 * the defaults in application.yml.
 *
 * @param publishDuration  ADR-012 phase 1: minimum time K2 must sit
 *                         PUBLISHED (JWKS-visible) before CUTOVER, so every
 *                         gateway has cached it. Must exceed the gateway's
 *                         JWKS cache TTL (5 min); ADR-012's default is 15.
 * @param drainDuration    ADR-012 phase 3: minimum time to wait after
 *                         CUTOVER before RETIRE, so every outstanding
 *                         K1-signed token has expired. Must be >= the
 *                         access-token TTL; ADR-012's default is 30 min.
 * @param schedulerInterval how often the scheduled job checks whether the
 *                          current phase's minimum duration has elapsed.
 */
@ConfigurationProperties(prefix = "auth.jwt.rotation")
@Validated
record RotationProperties(
        @NotNull Duration publishDuration,
        @NotNull Duration drainDuration,
        @NotNull Duration schedulerInterval) {
}

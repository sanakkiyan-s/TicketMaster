package com.ticketmaster.auth.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Token configuration, validated at startup.
 *
 * `@Validated` on the config rather than checks inside the issuer is the
 * point: a blank issuer or missing audience becomes a refusal to start, not a
 * run of tokens the gateway later refuses. Nothing here carries a default — a
 * silently defaulted `iss` is exactly the kind of value that ships wrong.
 *
 * @param issuer         `iss` claim. Gateways match on it, so it must be the
 *                       same string everywhere within an environment.
 * @param audience       `aud` claim, naming who may consume the token.
 * @param accessTokenTtl ADR-012's 10 minutes, and the number the rotation
 *                       schedule derives from: phase 3 DRAIN must be at least
 *                       this long, or a live token outlives the key that
 *                       signed it.
 * @param keySource      `ephemeral` (dev, in-memory) or `vault` (ADR-010).
 */
@ConfigurationProperties(prefix = "auth.jwt")
@Validated
record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl,
        @NotBlank String keySource
) {
}

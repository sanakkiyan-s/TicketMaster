package com.ticketmaster.gateway.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param jwksUri          Where auth-service publishes its public keys.
 * @param issuer           Expected `iss`. A token from another issuer that
 *                         happens to be signed by a key we trust must still be
 *                         rejected.
 * @param audience         Expected `aud`. Stops a token minted for a different
 *                         audience by the same issuer being replayed here.
 * @param refreshInterval  Background JWKS refresh. ADR-012 fixes this at 5 min
 *                         and derives the rotation phase length from it, so
 *                         raising it silently lengthens the window in which a
 *                         rotation can strand this instance.
 * @param clockSkew        Tolerance on exp/iat. Small on purpose: it directly
 *                         extends the life of every expired token.
 * @param unknownKidTtl    How long an unknown `kid` is remembered so it is not
 *                         re-logged on every request during a flood.
 * @param publicPaths      Paths that bypass validation entirely - the endpoints
 *                         where credentials are exchanged, which by definition
 *                         cannot require a token.
 */
@ConfigurationProperties(prefix = "gateway.jwt")
public record JwtValidationProperties(
        String jwksUri,
        String issuer,
        String audience,
        Duration refreshInterval,
        Duration clockSkew,
        Duration unknownKidTtl,
        List<String> publicPaths
) {
}

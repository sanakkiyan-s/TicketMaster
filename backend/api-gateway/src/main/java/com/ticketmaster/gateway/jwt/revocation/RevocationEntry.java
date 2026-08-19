package com.ticketmaster.gateway.jwt.revocation;

import java.time.Instant;

/**
 * One materialized row of the {@code auth.revocation} topic (ADR-012).
 *
 * @param revokeBefore any token with {@code iat} strictly before this instant
 *                      is rejected. Tokens issued AT OR AFTER this instant are
 *                      a fresh login/refresh that happened after the ban and
 *                      must keep working.
 * @param reason        free-text, carried only for logs/alarms - never
 *                       returned to a client.
 */
public record RevocationEntry(Instant revokeBefore, String reason) {
}

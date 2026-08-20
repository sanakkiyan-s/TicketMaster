package com.ticketmaster.gateway.jwt.revocation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param topic                Compacted Kafka topic auth-service's outbox
 *                              publishes bans/logouts onto (ADR-012).
 * @param consumerGroupPrefix  Prefix only - {@link RevocationConsumer}
 *                              appends a random suffix per JVM so every
 *                              gateway instance gets its OWN consumer group
 *                              and always replays from earliest on startup,
 *                              matching ADR-012's "every api-gateway
 *                              instance, own consumer group, reads from
 *                              earliest" requirement. A shared group id
 *                              would make instances split the partitions
 *                              between them instead of each holding the
 *                              full map.
 * @param maxTokenLifetime      A revocation entry only matters while a token
 *                              issued before {@code revokeBefore} could still
 *                              be alive. Tombstoned past that (ADR-012's
 *                              bounded-memory argument). Must be >= the
 *                              longest-lived access token this gateway will
 *                              ever see.
 * @param sweepInterval         How often the tombstone sweep runs.
 */
@ConfigurationProperties(prefix = "gateway.revocation")
public record RevocationProperties(
        String topic,
        String consumerGroupPrefix,
        Duration maxTokenLifetime,
        Duration sweepInterval
) {
}

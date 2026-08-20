package com.ticketmaster.auth.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Records an `AdminActionPerformed`-shaped audit trail (ADR-030,
 * cross-cutting-concerns.md's "Audit logging" section) for every admin
 * action in this service: key rotation, compromise response, user bans.
 *
 * A structured log line only. cross-cutting-concerns.md describes
 * `AdminActionPerformed` in prose (actor id, action, target, timestamp,
 * reason) but no ADR gives it a field-by-field schema the way ADR-012 does
 * for the revocation record, so this is a reasonable-call implementation,
 * not a contract other services can parse yet. A real event-bus publish
 * (an outbox row + Kafka topic, the same mechanism {@code RevocationPublisher}
 * uses) is explicitly out of scope for this slice.
 */
@Component
public class AdminActionAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    public void record(String actorUserId, String action, String target, String reason) {
        log.info("AdminActionPerformed actor={} action={} target={} reason={} at={}",
                actorUserId, action, target, reason == null ? "" : reason, Instant.now());
    }
}

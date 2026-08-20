package com.ticketmaster.event.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The application half of ADR-007's transactional outbox, generalised
 * across event-service's four producer event types (event.created,
 * event.updated, event.cancelled, session.flagged_high_demand — see
 * V3__outbox.sql's header) rather than specialised to one, the way
 * auth-service's RevocationPublisher is to auth.revocation. Callers supply
 * the `eventType`, `aggregateId` (the event or session id as a string) and
 * a payload map; this class only handles the write mechanics.
 *
 * Debezium (infra, not this service) tails Postgres's WAL and publishes the
 * committed row to Kafka via the outbox event-router SMT - this class never
 * touches Kafka. Registering that Kafka Connect connector against this
 * table is a deployment step this service's code cannot perform.
 */
@Component
public class OutboxPublisher {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OutboxPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * MANDATORY, not REQUIRED: this write is only correct inside the SAME
     * transaction as whatever triggered the domain event (an event
     * create/update/cancel). Silently opening its own transaction here
     * would defeat the entire point of the outbox pattern - a crash
     * between two separately-committed transactions could still lose the
     * event even though the business change persisted. Calling this with
     * no transaction already active is a caller bug, not a case to paper
     * over with REQUIRED's default of "start one if missing".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, String aggregateId, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize outbox payload", e);
        }

        outbox.save(new OutboxEvent(
                UUID.randomUUID(), aggregateId, eventType, json, traceparent(), null, Instant.now(clock)));
    }

    /**
     * ADR-015's OTel Java agent (see backend/Dockerfile) generates a real
     * W3C traceparent on every request and this repo's services all run
     * behind it, so the forwarded header below is live real trace data on
     * event-service's own requests, not a future-proofing placeholder -
     * see RevocationPublisher's equivalent method in auth-service, whose
     * comment predates ADR-015 and is now stale there too. The all-zero
     * fallback stays for the one real gap: a call with no bound HTTP
     * request context (e.g. invoked from a non-HTTP path), where there is
     * genuinely no traceparent to forward and inventing one would look
     * like real trace data.
     */
    private String traceparent() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String header = attrs.getRequest().getHeader("traceparent");
                if (header != null && !header.isBlank()) {
                    return header;
                }
            }
        } catch (IllegalStateException e) {
            // No request context bound (e.g. called from a non-HTTP path) - fall through.
        }
        return "00-" + "0".repeat(32) + "-" + "0".repeat(16) + "-00";
    }
}

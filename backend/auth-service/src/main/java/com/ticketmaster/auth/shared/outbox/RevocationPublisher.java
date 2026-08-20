package com.ticketmaster.auth.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The application half of ADR-007's transactional outbox, specialised to
 * ADR-012's revocation record: writes exactly one `outbox` row inside the
 * caller's own transaction, `event_type = "auth.revocation"`, `aggregate_id`
 * = the revocation scope (`"user:&lt;uuid&gt;"` or `"session:&lt;sid&gt;"`),
 * `payload = {"revokeBefore": &lt;epoch millis&gt;, "reason": "..."}`.
 *
 * Debezium (infra, not this service) tails Postgres's WAL and publishes the
 * committed row to Kafka via the outbox event-router SMT - this class never
 * touches Kafka. Registering that Kafka Connect connector against this
 * table is a deployment step this service's code cannot perform.
 */
@Component
public class RevocationPublisher {

    private static final String EVENT_TYPE = "auth.revocation";

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    RevocationPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * MANDATORY, not REQUIRED: this write is only correct inside the SAME
     * transaction as whatever triggered the revocation (a logout, a
     * logout-everywhere, an admin ban). Silently opening its own
     * transaction here would defeat the entire point of the outbox pattern
     * - a crash between two separately-committed transactions could still
     * lose the event even though the business change persisted. Calling
     * this with no transaction already active is a caller bug, not a case
     * to paper over with REQUIRED's default of "start one if missing".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishRevocation(String scope, Instant revokeBefore, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revokeBefore", revokeBefore.toEpochMilli());
        payload.put("reason", reason);

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize revocation payload", e);
        }

        outbox.save(new OutboxEvent(
                UUID.randomUUID(), scope, EVENT_TYPE, json, traceparent(), null, Instant.now(clock)));
    }

    /**
     * Simplification, stated explicitly (per the task's instructions): no
     * distributed-tracing infrastructure exists anywhere in this codebase
     * yet - no span, no W3C traceparent generation at api-gateway, no
     * correlation-id header read anywhere in auth-service as of this
     * slice (cross-cutting-concerns.md lists tracing tooling as
     * "not yet decided"). This opportunistically forwards an incoming
     * `traceparent` header if a caller already sends one (future-proofing
     * for when api-gateway starts generating one, per ADR-007's amendment),
     * and otherwise writes a clearly all-zero placeholder trace id rather
     * than inventing a fake one that would look like real trace data.
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

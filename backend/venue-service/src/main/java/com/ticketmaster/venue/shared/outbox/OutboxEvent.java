package com.ticketmaster.venue.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of ADR-007's transactional outbox. Debezium (infra, not this
 * service) tails Postgres's WAL and publishes each committed row to Kafka;
 * this service only ever writes here.
 *
 * `payload` is mapped as a plain {@code String} with a write-side cast
 * ({@code ?::jsonb}) rather than a JSON-typed field, so the caller controls
 * serialization explicitly (via Jackson in {@link OutboxPublisher}) and
 * the column still stores real `jsonb`, not text - Postgres itself validates
 * the JSON is well-formed on insert.
 */
@Entity
@Table(name = "outbox")
@Getter
class OutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(name = "traceparent", nullable = false)
    private String traceparent;

    @Column(name = "tracestate")
    private String tracestate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEvent() {
        // JPA
    }

    OutboxEvent(UUID eventId, String aggregateId, String eventType, String payload,
                String traceparent, String tracestate, Instant createdAt) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.traceparent = traceparent;
        this.tracestate = tracestate;
        this.createdAt = createdAt;
    }
}

package com.ticketmaster.inventory.kafka;

import com.ticketmaster.inventory.avro.SeatEvent;
import com.ticketmaster.inventory.avro.SeatEventType;
import com.ticketmaster.inventory.avro.SeatStatus;
import com.ticketmaster.inventory.seat.SeatId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * First real Avro producer in this codebase — every other Kafka-producing
 * service scaffold declares the same kafka-avro-serializer dependency but
 * none actually generate/produce Avro yet (event-service's outbox goes
 * through Debezium with StringConverter instead). Partition key is
 * sessionId+seatId so per-seat ordering is preserved across the topic's
 * partitions - two events for the same seat must never be seen
 * out-of-order by search-service's future consumer or this service's own
 * SSE fan-out.
 *
 * Publish failures are logged and swallowed, never thrown back into the
 * hold/confirm request path: a buyer's hold must succeed on Postgres's
 * word alone (that's the correctness authority per ADR-002) even if the
 * live-update side-channel is degraded. The SSE fan-out has its own
 * independent path (SeatEventBroadcaster consumes the same topic), so a
 * publish failure here means other tabs miss one live update, not that
 * the hold silently failed.
 */
@Component
public class SeatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SeatEventPublisher.class);
    public static final String TOPIC = "seat.events";

    private final KafkaTemplate<String, SeatEvent> kafka;
    private final Clock clock;

    public SeatEventPublisher(KafkaTemplate<String, SeatEvent> kafka, Clock clock) {
        this.kafka = kafka;
        this.clock = clock;
    }

    public void publishSeatUpdated(SeatId id, com.ticketmaster.inventory.seat.SeatStatus status) {
        publish(id, SeatEventType.SEAT_UPDATED, toAvroStatus(status));
    }

    public void publishHoldExpired(SeatId id) {
        publish(id, SeatEventType.HOLD_EXPIRED, null);
    }

    private void publish(SeatId id, SeatEventType type, SeatStatus status) {
        SeatEvent event = SeatEvent.newBuilder()
                .setSessionId(id.getSessionId().toString())
                .setSeatId(id.getSeatId().toString())
                .setEventType(type)
                .setStatus(status)
                .setOccurredAt(Instant.now(clock))
                .build();

        String key = id.getSessionId() + ":" + id.getSeatId();
        kafka.send(TOPIC, key, event).exceptionally(ex -> {
            log.warn("failed to publish {} for session={} seat={} - live update side-channel degraded, "
                    + "hold/confirm decision itself is unaffected", type, id.getSessionId(), id.getSeatId(), ex);
            return null;
        });
    }

    private static SeatStatus toAvroStatus(com.ticketmaster.inventory.seat.SeatStatus status) {
        return SeatStatus.valueOf(status.name());
    }
}

package com.ticketmaster.search.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes event-service's outbox stream and upserts search-service's own
 * denormalized index — never reads event-service's Postgres directly
 * (search architecture doc, second-brain/wiki/architecture/
 * final-architecture-reference.md, "Search never reads event-service's
 * Postgres directly").
 *
 * A standard {@code @KafkaListener}, not a raw KafkaConsumer like
 * api-gateway's RevocationConsumer — that class needs manual offset/
 * partition control for its compacted-topic replay-to-caught-up
 * semantics; this consumer has no equivalent requirement, so the simpler
 * default is the right one here.
 */
@Component
public class EventKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventKafkaConsumer.class);
    private static final String DEFAULT_CANCELLED_STATUS = "CANCELLED";

    private final EventDocumentRepository events;
    private final ObjectMapper objectMapper;

    public EventKafkaConsumer(EventDocumentRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"event.created", "event.updated"},
            groupId = "${spring.kafka.consumer.group-id}")
    public void onEventUpserted(String message) {
        parse(message).ifPresent(this::upsert);
    }

    @KafkaListener(topics = "event.cancelled", groupId = "${spring.kafka.consumer.group-id}")
    public void onEventCancelled(String message) {
        parse(message).ifPresent(this::applyCancellation);
    }

    private void upsert(Map<String, Object> payload) {
        String eventId = stringField(payload, "eventId");
        if (eventId == null) {
            log.warn("discarding event message with no eventId field, skipping index update");
            return;
        }
        events.save(new EventDocument(
                eventId,
                stringField(payload, "organizerId"),
                stringField(payload, "venueId"),
                stringField(payload, "title"),
                stringField(payload, "status"),
                stringField(payload, "region")));
    }

    private void applyCancellation(Map<String, Object> payload) {
        String eventId = stringField(payload, "eventId");
        if (eventId == null) {
            log.warn("discarding event.cancelled message with no eventId field, skipping index update");
            return;
        }
        String status = stringField(payload, "status");
        String resolvedStatus = status != null ? status : DEFAULT_CANCELLED_STATUS;

        Optional<EventDocument> merged = events.findById(eventId)
                .map(existing -> existing.withStatus(resolvedStatus));
        if (merged.isPresent()) {
            events.save(merged.get());
            return;
        }

        // No prior document to merge status into (e.g. index rebuild, or
        // this consumer's group never saw the created/updated message) —
        // index whatever the cancellation payload itself carries rather
        // than dropping the event from search entirely.
        events.save(new EventDocument(
                eventId,
                stringField(payload, "organizerId"),
                stringField(payload, "venueId"),
                stringField(payload, "title"),
                resolvedStatus,
                stringField(payload, "region")));
    }

    /**
     * A poison message (malformed JSON, wrong shape) must never take down
     * the consumer thread — catching broadly here, logging, and returning
     * empty is the deliberate boundary, not a general silent-swallow
     * pattern used elsewhere in this codebase.
     */
    private Optional<Map<String, Object>> parse(String message) {
        try {
            return Optional.of(objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception ex) {
            log.warn("discarding malformed event payload, skipping index update: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }
}

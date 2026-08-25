package com.ticketmaster.inventory.sse;

import com.ticketmaster.inventory.avro.SeatEvent;
import com.ticketmaster.inventory.avro.SeatEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Consumes this service's own seat.events topic and fans each message out
 * to every browser tab currently subscribed to that session's SSE stream
 * (ADR-022). Going through Kafka rather than an in-process call from
 * SeatInventoryService directly is deliberate: it's what makes this
 * correct once inventory-service runs as more than one replica — a hold
 * placed on instance A must still reach a browser tab whose SSE
 * connection landed on instance B, and only the shared Kafka topic
 * crosses that boundary.
 */
@Component
public class SeatEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SeatEventBroadcaster.class);

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> sessionEmitters = subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        sessionEmitters.add(emitter);

        Runnable cleanup = () -> sessionEmitters.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        return emitter;
    }

    @KafkaListener(topics = com.ticketmaster.inventory.kafka.SeatEventPublisher.TOPIC,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onSeatEvent(SeatEvent event) {
        List<SseEmitter> sessionEmitters = subscribers.get(event.getSessionId().toString());
        if (sessionEmitters == null || sessionEmitters.isEmpty()) {
            return;
        }

        String payload = toFrontendJson(event);
        for (SseEmitter emitter : sessionEmitters) {
            try {
                emitter.send(SseEmitter.event().name("seat").data(payload));
            } catch (IOException e) {
                // Dead connection - onError/onCompletion above already
                // schedule its removal; nothing more to do here.
                log.debug("SSE send failed for session={}, connection likely closed", event.getSessionId());
            }
        }
    }

    /** Mirrors frontend types.ts's SeatLiveEvent discriminated union exactly. */
    private static String toFrontendJson(SeatEvent event) {
        if (event.getEventType() == SeatEventType.HOLD_EXPIRED) {
            return "{\"type\":\"hold-expired\",\"seatId\":\"" + event.getSeatId() + "\"}";
        }
        return "{\"type\":\"seat-updated\",\"seatId\":\"" + event.getSeatId()
                + "\",\"status\":\"" + event.getStatus() + "\"}";
    }
}

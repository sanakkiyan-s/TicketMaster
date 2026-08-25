package com.ticketmaster.inventory.sse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ADR-022: SSE, not WebSocket. Replaces the frontend's mock
 * (useSeatLiveUpdates in api.ts, currently a setInterval simulation) once
 * routed — same two event shapes (seat-updated/hold-expired), no
 * frontend change needed.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/seats")
public class SeatStreamController {

    private final SeatEventBroadcaster broadcaster;

    public SeatStreamController(SeatEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable String sessionId) {
        return broadcaster.subscribe(sessionId);
    }
}

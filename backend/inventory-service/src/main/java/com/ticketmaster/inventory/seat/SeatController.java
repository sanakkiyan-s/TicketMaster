package com.ticketmaster.inventory.seat;

import com.ticketmaster.inventory.shared.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ADR-034 versions the REST edge at /api/v1. Sits under the plain
 * (non-/organizer) prefix, same convention as auth's login/refresh — a
 * buyer, not an organizer, calls these. Frontend contract:
 * frontend/src/features/booking/api.ts + src/mocks/handlers.ts — this
 * controller replaces the mock, identical response shapes.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/seats")
public class SeatController {

    private final SeatInventoryService inventory;
    private final CurrentUserResolver currentUser;

    public SeatController(SeatInventoryService inventory, CurrentUserResolver currentUser) {
        this.inventory = inventory;
        this.currentUser = currentUser;
    }

    @GetMapping
    public SessionSeatMapResponse getSeatMap(@PathVariable UUID sessionId, HttpServletRequest request) {
        return inventory.getSeatMap(sessionId, currentUser.resolve(request));
    }

    /**
     * The frontend route (/events/:eventId/sessions/:sessionId/seats)
     * already has eventId one hop up, but api.ts's hold mutation only
     * passes sessionId+seatId (see HoldSeat's mutationFn) — recovering
     * eventId server-side here is cheaper than widening that contract
     * for a value the seat rows already carry per-row.
     */
    @PostMapping("/{seatId}/hold")
    public HoldResponse hold(@PathVariable UUID sessionId, @PathVariable UUID seatId, HttpServletRequest request) {
        UUID callerId = currentUser.resolve(request);
        UUID eventId = inventory.eventIdFor(sessionId);
        return inventory.holdSeat(eventId, sessionId, seatId, callerId);
    }

    @PostMapping("/{seatId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable UUID sessionId, @PathVariable UUID seatId, HttpServletRequest request) {
        UUID callerId = currentUser.resolve(request);
        UUID eventId = inventory.eventIdFor(sessionId);
        inventory.confirmSeat(eventId, sessionId, seatId, callerId);
        return ResponseEntity.noContent().build();
    }

    @RestController
    @RequestMapping("/api/v1/organizer/sessions/{sessionId}/seats")
    static class OrganizerSeatController {

        private final SeatInventoryService inventory;

        OrganizerSeatController(SeatInventoryService inventory) {
            this.inventory = inventory;
        }

        /**
         * Coarse ORGANIZER role gate only (ADR-030 layer 1) — no
         * fine-grained ownership check against event-service's
         * organizer_id yet, since that would mean a cross-service call
         * this codebase has no established pattern for anywhere else.
         * Noted as a real gap, not silently skipped.
         */
        @PostMapping("/seed")
        public ResponseEntity<Void> seed(@PathVariable UUID sessionId, @Valid @RequestBody SeedSessionRequest request) {
            inventory.seedSession(sessionId, request);
            return ResponseEntity.noContent().build();
        }
    }
}

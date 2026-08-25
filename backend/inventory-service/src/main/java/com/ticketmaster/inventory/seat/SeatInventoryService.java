package com.ticketmaster.inventory.seat;

import com.ticketmaster.inventory.kafka.SeatEventPublisher;
import com.ticketmaster.inventory.redis.SeatLockGate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADR-002's concurrency core. hold/confirm each take the real Postgres
 * row lock regardless of the Redis fast-gate's outcome — the gate only
 * decides whether a losing attempt pays for that lock wait or gets
 * rejected in microseconds first.
 */
@Service
public class SeatInventoryService {

    /** ADR-002: flat 5-minute base hold, no demand-tiering. */
    static final Duration BASE_HOLD_TTL = Duration.ofMinutes(5);

    private final SeatRepository seats;
    private final PriceTierRepository priceTiers;
    private final SeatLockGate lockGate;
    private final SeatEventPublisher events;
    private final Clock clock;

    public SeatInventoryService(SeatRepository seats, PriceTierRepository priceTiers,
                                 SeatLockGate lockGate, SeatEventPublisher events, Clock clock) {
        this.seats = seats;
        this.priceTiers = priceTiers;
        this.lockGate = lockGate;
        this.events = events;
        this.clock = clock;
    }

    /** Explicit, one-time seed — see SeedSessionRequest's javadoc for why this exists instead of an automatic pipeline. */
    @Transactional
    public void seedSession(UUID sessionId, SeedSessionRequest request) {
        if (seats.existsById_SessionId(sessionId)) {
            throw new SeatNotAvailableException("session " + sessionId + " already has seat inventory seeded");
        }

        for (SeedSessionRequest.PriceTierSeed tier : request.priceTiers()) {
            priceTiers.save(new PriceTier(tier.id(), sessionId, tier.label(), tier.priceCents()));
        }

        for (SeedSessionRequest.SectionSeed section : request.sections()) {
            for (int row = 1; row <= section.rows(); row++) {
                for (int col = 1; col <= section.cols(); col++) {
                    SeatId id = new SeatId(request.eventId(), sessionId, UUID.randomUUID());
                    seats.save(new Seat(id, section.id(), section.name(), row, col, section.priceTierId()));
                }
            }
        }
    }

    /** Recovers a session's event_id for callers that only have the sessionId — see SeatController's javadoc. */
    @Transactional(readOnly = true)
    public UUID eventIdFor(UUID sessionId) {
        return seats.findFirstById_SessionId(sessionId)
                .map(seat -> seat.getId().getEventId())
                .orElseThrow(() -> new SeatNotFoundException("no seat inventory for session " + sessionId));
    }

    @Transactional(readOnly = true)
    public SessionSeatMapResponse getSeatMap(UUID sessionId, UUID callerId) {
        List<Seat> sessionSeats = seats.findById_SessionIdOrderBySectionNameAscRowNumberAscColNumberAsc(sessionId);
        if (sessionSeats.isEmpty()) {
            throw new SeatNotFoundException("no seat inventory for session " + sessionId);
        }

        UUID eventId = sessionSeats.get(0).getId().getEventId();

        Map<String, List<SeatResponse>> bySection = new LinkedHashMap<>();
        Map<String, String> sectionNames = new LinkedHashMap<>();
        for (Seat seat : sessionSeats) {
            String sectionId = seat.getSectionId().toString();
            sectionNames.putIfAbsent(sectionId, seat.getSectionName());
            bySection.computeIfAbsent(sectionId, k -> new java.util.ArrayList<>())
                    .add(SeatResponse.from(seat, callerId));
        }

        List<SectionResponse> sections = bySection.entrySet().stream()
                .map(e -> new SectionResponse(e.getKey(), sectionNames.get(e.getKey()), e.getValue()))
                .toList();

        List<PriceTierResponse> tiers = priceTiers.findBySessionId(sessionId).stream()
                .sorted(Comparator.comparing(PriceTier::getPriceCents).reversed())
                .map(PriceTierResponse::from)
                .collect(Collectors.toList());

        return new SessionSeatMapResponse(sessionId.toString(), eventId.toString(), tiers, sections);
    }

    /**
     * ADR-002's hold(seatId) pseudocode: Redis fast-gate first, then the
     * real Postgres {@code SELECT ... FOR UPDATE}. A gate rejection is a
     * fast 409 — no Postgres connection spent on a losing attempt. A gate
     * win still re-checks AVAILABLE under the row lock, since the gate is
     * only ever a hint, never authoritative.
     */
    @Transactional
    public HoldResponse holdSeat(UUID eventId, UUID sessionId, UUID seatId, UUID callerId) {
        if (!lockGate.tryAcquire(sessionId, seatId, callerId)) {
            throw new SeatNotAvailableException("seat " + seatId + " is being held by another request right now");
        }

        try {
            SeatId id = new SeatId(eventId, sessionId, seatId);
            Seat seat = seats.findByIdForUpdate(id)
                    .orElseThrow(() -> new SeatNotFoundException("seat " + seatId + " not found"));

            Instant now = Instant.now(clock);
            if (seat.isExpired(now)) {
                seat.releaseExpiredHold();
                events.publishHoldExpired(id);
            }

            if (!seat.isAvailable()) {
                throw new SeatNotAvailableException("seat " + seatId + " is not available (status: " + seat.getStatus() + ")");
            }

            Instant heldUntil = now.plus(BASE_HOLD_TTL);
            seat.hold(callerId, heldUntil);
            events.publishSeatUpdated(id, SeatStatus.HELD);

            return new HoldResponse(seatId.toString(), SeatStatus.HELD, heldUntil);
        } finally {
            lockGate.release(sessionId, seatId);
        }
    }

    /**
     * ADR-002's confirm(seatId, callerId) — booking-service's future
     * entry point once it exists (Phase 3, not built yet). Exposed now so
     * this service's own contract is complete; nothing calls it in
     * production yet.
     */
    @Transactional
    public void confirmSeat(UUID eventId, UUID sessionId, UUID seatId, UUID callerId) {
        SeatId id = new SeatId(eventId, sessionId, seatId);
        Seat seat = seats.findByIdForUpdate(id)
                .orElseThrow(() -> new SeatNotFoundException("seat " + seatId + " not found"));

        Instant now = Instant.now(clock);
        if (seat.isExpired(now)) {
            seat.releaseExpiredHold();
            events.publishHoldExpired(id);
            throw new SeatNotAvailableException(
                    "hold on seat " + seatId + " expired before payment confirmed - see ADR-002's payment race, "
                            + "caller must trigger a refund");
        }

        if (!seat.isHeldBy(callerId)) {
            throw new SeatNotAvailableException("seat " + seatId + " is not held by this caller");
        }

        seat.confirm();
        events.publishSeatUpdated(id, SeatStatus.PURCHASED);
    }
}

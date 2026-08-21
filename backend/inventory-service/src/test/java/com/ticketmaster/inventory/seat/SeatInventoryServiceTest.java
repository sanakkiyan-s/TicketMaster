package com.ticketmaster.inventory.seat;

import com.ticketmaster.inventory.kafka.SeatEventPublisher;
import com.ticketmaster.inventory.redis.SeatLockGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Focused on ADR-002's hold/confirm state-machine logic — the
 * concurrency-critical part of this slice. Row-locking itself
 * (SELECT ... FOR UPDATE) isn't exercised here since it needs a real
 * Postgres connection, not a mock — that belongs in an integration test
 * against a Citus-enabled container per the ADR's amendment, not built
 * this pass.
 */
@ExtendWith(MockitoExtension.class)
class SeatInventoryServiceTest {

    @Mock
    SeatRepository seats;

    @Mock
    PriceTierRepository priceTiers;

    @Mock
    SeatLockGate lockGate;

    @Mock
    SeatEventPublisher events;

    SeatInventoryService inventory;

    UUID eventId;
    UUID sessionId;
    UUID seatId;
    UUID callerId;
    SeatId id;
    Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC);
        inventory = new SeatInventoryService(seats, priceTiers, lockGate, events, clock);

        eventId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        seatId = UUID.randomUUID();
        callerId = UUID.randomUUID();
        id = new SeatId(eventId, sessionId, seatId);
    }

    @Test
    void holdingAnAvailableSeatSucceedsAndPublishesSeatUpdated() {
        when(lockGate.tryAcquire(sessionId, seatId, callerId)).thenReturn(true);
        Seat seat = new Seat(id, UUID.randomUUID(), "Floor", 1, 1, UUID.randomUUID());
        when(seats.findByIdForUpdate(id)).thenReturn(Optional.of(seat));

        HoldResponse response = inventory.holdSeat(eventId, sessionId, seatId, callerId);

        assertThat(response.status()).isEqualTo(SeatStatus.HELD);
        assertThat(response.heldUntil()).isEqualTo(Instant.now(clock).plus(Duration.ofMinutes(5)));
        org.mockito.Mockito.verify(events).publishSeatUpdated(id, SeatStatus.HELD);
        org.mockito.Mockito.verify(lockGate).release(sessionId, seatId);
    }

    @Test
    void redisGateRejectionFailsFastWithoutTouchingTheRowLock() {
        when(lockGate.tryAcquire(sessionId, seatId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> inventory.holdSeat(eventId, sessionId, seatId, callerId))
                .isInstanceOf(SeatNotAvailableException.class);

        org.mockito.Mockito.verify(seats, org.mockito.Mockito.never()).findByIdForUpdate(any());
    }

    @Test
    void holdingAnAlreadyHeldSeatIsRejectedEvenIfTheRedisGateWonRace() {
        when(lockGate.tryAcquire(sessionId, seatId, callerId)).thenReturn(true);
        Seat seat = new Seat(id, UUID.randomUUID(), "Floor", 1, 1, UUID.randomUUID());
        seat.hold(UUID.randomUUID(), Instant.now(clock).plus(Duration.ofMinutes(5)));
        when(seats.findByIdForUpdate(id)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> inventory.holdSeat(eventId, sessionId, seatId, callerId))
                .isInstanceOf(SeatNotAvailableException.class);
    }

    @Test
    void holdingASeatWhoseExpiredHoldWasNeverSweptReleasesItFirstThenSucceeds() {
        when(lockGate.tryAcquire(sessionId, seatId, callerId)).thenReturn(true);
        Seat seat = new Seat(id, UUID.randomUUID(), "Floor", 1, 1, UUID.randomUUID());
        seat.hold(UUID.randomUUID(), Instant.now(clock).minus(Duration.ofMinutes(1)));
        when(seats.findByIdForUpdate(id)).thenReturn(Optional.of(seat));

        HoldResponse response = inventory.holdSeat(eventId, sessionId, seatId, callerId);

        assertThat(response.status()).isEqualTo(SeatStatus.HELD);
        org.mockito.Mockito.verify(events).publishHoldExpired(id);
    }

    @Test
    void confirmingASeatHeldByTheCallerSucceeds() {
        Seat seat = new Seat(id, UUID.randomUUID(), "Floor", 1, 1, UUID.randomUUID());
        seat.hold(callerId, Instant.now(clock).plus(Duration.ofMinutes(5)));
        when(seats.findByIdForUpdate(id)).thenReturn(Optional.of(seat));

        inventory.confirmSeat(eventId, sessionId, seatId, callerId);

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.PURCHASED);
        org.mockito.Mockito.verify(events).publishSeatUpdated(id, SeatStatus.PURCHASED);
    }

    @Test
    void confirmingASeatHeldBySomeoneElseIsRejected() {
        Seat seat = new Seat(id, UUID.randomUUID(), "Floor", 1, 1, UUID.randomUUID());
        seat.hold(UUID.randomUUID(), Instant.now(clock).plus(Duration.ofMinutes(5)));
        when(seats.findByIdForUpdate(id)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> inventory.confirmSeat(eventId, sessionId, seatId, callerId))
                .isInstanceOf(SeatNotAvailableException.class);
    }

    @Test
    void confirmingAnExpiredHoldIsRejectedAndReleasesTheSeat() {
        Seat seat = new Seat(id, UUID.randomUUID(), "Floor", 1, 1, UUID.randomUUID());
        seat.hold(callerId, Instant.now(clock).minus(Duration.ofMinutes(1)));
        when(seats.findByIdForUpdate(id)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> inventory.confirmSeat(eventId, sessionId, seatId, callerId))
                .isInstanceOf(SeatNotAvailableException.class);

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        org.mockito.Mockito.verify(events).publishHoldExpired(id);
    }

    @Test
    void seedingATwiceSeededSessionIsRejected() {
        when(seats.existsById_SessionId(sessionId)).thenReturn(true);
        SeedSessionRequest request = new SeedSessionRequest(
                eventId,
                java.util.List.of(new SeedSessionRequest.PriceTierSeed(UUID.randomUUID(), "Floor", 15000)),
                java.util.List.of());

        assertThatThrownBy(() -> inventory.seedSession(sessionId, request))
                .isInstanceOf(SeatNotAvailableException.class);
    }
}

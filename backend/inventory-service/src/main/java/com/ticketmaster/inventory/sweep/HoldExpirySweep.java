package com.ticketmaster.inventory.sweep;

import com.ticketmaster.inventory.kafka.SeatEventPublisher;
import com.ticketmaster.inventory.seat.Seat;
import com.ticketmaster.inventory.seat.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * ADR-002's periodic expiry sweep — the backstop that reclaims a HELD
 * seat nobody ever came back to confirm/re-touch. Most expired holds are
 * actually caught lazily instead (SeatInventoryService.holdSeat/
 * confirmSeat both check isExpired on read), so this sweep mainly matters
 * for a seat nobody ever requests again — otherwise unreachable code
 * path, still needed so an abandoned seat doesn't stay HELD forever.
 */
@Component
public class HoldExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweep.class);
    private static final int BATCH_SIZE = 200;

    private final SeatRepository seats;
    private final SeatEventPublisher events;
    private final Clock clock;

    public HoldExpirySweep(SeatRepository seats, SeatEventPublisher events, Clock clock) {
        this.seats = seats;
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void sweep() {
        Instant now = Instant.now(clock);
        List<Seat> expired = seats.findExpiredHeldForUpdate(now, BATCH_SIZE);

        for (Seat seat : expired) {
            seat.releaseExpiredHold();
            events.publishHoldExpired(seat.getId());
        }

        if (!expired.isEmpty()) {
            log.info("expiry sweep released {} seat(s)", expired.size());
        }
    }
}

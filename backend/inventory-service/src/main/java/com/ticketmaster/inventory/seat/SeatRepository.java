package com.ticketmaster.inventory.seat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {

    /** ADR-002's {@code SELECT ... FOR UPDATE} — the actual concurrency-control step for hold/confirm. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") SeatId id);

    List<Seat> findById_SessionIdOrderBySectionNameAscRowNumberAscColNumberAsc(UUID sessionId);

    boolean existsById_SessionId(UUID sessionId);

    /** Cheap event_id lookup for a session without materializing the whole seat map — see SeatController's javadoc. */
    Optional<Seat> findFirstById_SessionId(UUID sessionId);

    /**
     * Expiry sweep's read path — {@code SKIP LOCKED} so a sweep instance
     * never blocks on a row a concurrent hold/confirm is actively touching;
     * it simply picks that row up on the next tick instead (ADR-002).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT * FROM inventory_service.seats WHERE status = 'HELD' AND held_until < :now "
            + "FOR UPDATE SKIP LOCKED LIMIT :batchSize", nativeQuery = true)
    List<Seat> findExpiredHeldForUpdate(@Param("now") Instant now, @Param("batchSize") int batchSize);
}

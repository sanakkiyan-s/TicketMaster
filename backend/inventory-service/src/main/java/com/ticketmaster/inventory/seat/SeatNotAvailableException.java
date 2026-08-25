package com.ticketmaster.inventory.seat;

/**
 * Thrown when a hold/confirm is attempted against a seat that isn't in
 * the required state — someone else already holds/bought it, the caller's
 * own hold expired before they confirmed, or a redundant confirm. Maps to
 * 409 in ApiExceptionHandler. ADR-002 explicitly accepts this as a normal,
 * expected outcome during on-sale contention ("lost_race"), not an error.
 */
public class SeatNotAvailableException extends RuntimeException {
    public SeatNotAvailableException(String message) {
        super(message);
    }
}

package com.ticketmaster.inventory.seat;

/** Maps to 404 in ApiExceptionHandler. */
public class SeatNotFoundException extends RuntimeException {
    public SeatNotFoundException(String message) {
        super(message);
    }
}

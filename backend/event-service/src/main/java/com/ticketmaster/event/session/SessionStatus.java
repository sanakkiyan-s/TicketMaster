package com.ticketmaster.event.session;

/** Matches the `status` TEXT column's allowed values — see V1__baseline.sql's header comment. */
public enum SessionStatus {
    SCHEDULED,
    ON_SALE,
    CANCELLED,
    COMPLETED
}

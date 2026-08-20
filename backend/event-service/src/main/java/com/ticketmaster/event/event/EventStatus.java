package com.ticketmaster.event.event;

/** Matches the `status` TEXT column's allowed values — see V1__baseline.sql's header comment. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED
}

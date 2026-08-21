package com.ticketmaster.event.event;

/**
 * Thrown for a status transition that isn't allowed from the event's
 * current state — currently just publishing something other than a DRAFT
 * (a CANCELLED event must never become sellable again; an already-
 * PUBLISHED event re-publishing is a no-op the caller should not need,
 * not a silent success). Maps to 409 in shared/ApiExceptionHandler.
 */
public class InvalidEventStateException extends RuntimeException {
    public InvalidEventStateException(String message) {
        super(message);
    }
}

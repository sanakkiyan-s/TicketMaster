package com.ticketmaster.event.artist;

/**
 * Thrown for an unknown artist id. Artists are shared reference data, not
 * organizer-owned (no ADR-030 ownership check applies here) — this is a
 * plain 404, not part of the ownership-oracle-avoidance pattern the event
 * and session not-found exceptions follow.
 *
 * Public: shared/ApiExceptionHandler needs to reference it.
 */
public class ArtistNotFoundException extends RuntimeException {
    public ArtistNotFoundException(String message) {
        super(message);
    }
}

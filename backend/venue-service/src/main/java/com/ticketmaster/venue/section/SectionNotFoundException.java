package com.ticketmaster.venue.section;

/**
 * Thrown for "no such section" and "section's parent venue belongs to a
 * different organizer" alike — same 404-not-403 reasoning as
 * VenueNotFoundException, applied one level down: a section has no
 * organizer_id of its own, ownership is its parent venue's.
 *
 * Public: shared/ApiExceptionHandler needs to reference it.
 */
public class SectionNotFoundException extends RuntimeException {
    public SectionNotFoundException(String message) {
        super(message);
    }
}

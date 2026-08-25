package com.ticketmaster.event.session;

/**
 * Thrown for "no such session" and "session's parent event belongs to a
 * different organizer" alike — same 404-not-403 reasoning as
 * EventNotFoundException, applied one level down: a session has no
 * organizer_id of its own, ownership is its parent event's.
 *
 * Public: shared/ApiExceptionHandler needs to reference it.
 */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String message) {
        super(message);
    }
}

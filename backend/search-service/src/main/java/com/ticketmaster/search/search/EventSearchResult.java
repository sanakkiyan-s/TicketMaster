package com.ticketmaster.search.search;

import com.ticketmaster.search.event.EventDocument;

public record EventSearchResult(
        String eventId,
        String organizerId,
        String venueId,
        String title,
        String status,
        String region
) {
    public static EventSearchResult from(EventDocument document) {
        return new EventSearchResult(
                document.getEventId(),
                document.getOrganizerId(),
                document.getVenueId(),
                document.getTitle(),
                document.getStatus(),
                document.getRegion());
    }
}

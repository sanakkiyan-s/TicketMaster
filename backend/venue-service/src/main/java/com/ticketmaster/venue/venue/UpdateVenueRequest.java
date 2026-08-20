package com.ticketmaster.venue.venue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * organizer_id and region are not updatable here — region is data-residency
 * anchored at creation time (V1__baseline.sql's header note on ADR-016),
 * same reasoning as event-service's UpdateEventRequest.
 */
public record UpdateVenueRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 1024)
        String address,

        @Size(max = 128)
        String city
) {
}

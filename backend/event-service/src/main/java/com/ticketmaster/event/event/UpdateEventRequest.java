package com.ticketmaster.event.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * organizer_id and region are not updatable here — region is data-residency
 * anchored at creation time (V1__baseline.sql's header note on ADR-016),
 * ownership transfer is out of scope for this slice (ADR-030's Revisit
 * When section).
 */
public record UpdateEventRequest(

        @NotNull
        UUID venueId,

        @NotBlank
        @Size(max = 255)
        String title,

        @Size(max = 4096)
        String description,

        @Size(max = 64)
        String category
) {
}

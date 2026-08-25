package com.ticketmaster.event.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateEventRequest(

        @NotNull
        UUID venueId,

        @NotBlank
        @Size(max = 255)
        String title,

        @Size(max = 4096)
        String description,

        @Size(max = 64)
        String category,

        @NotBlank
        @Size(max = 32)
        String region
) {
}

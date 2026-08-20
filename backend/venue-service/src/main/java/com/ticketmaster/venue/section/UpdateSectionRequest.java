package com.ticketmaster.venue.section;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateSectionRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        @NotNull
        @Positive
        Integer capacity
) {
}

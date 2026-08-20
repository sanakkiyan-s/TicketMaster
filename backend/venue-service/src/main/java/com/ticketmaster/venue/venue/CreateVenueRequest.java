package com.ticketmaster.venue.venue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVenueRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 1024)
        String address,

        @Size(max = 128)
        String city,

        @NotBlank
        @Size(max = 32)
        String region
) {
}

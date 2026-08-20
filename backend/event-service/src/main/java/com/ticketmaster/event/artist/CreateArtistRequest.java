package com.ticketmaster.event.artist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateArtistRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 4096)
        String bio
) {
}

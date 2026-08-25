package com.ticketmaster.venue.seat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSeatRequest(

        @NotBlank
        @Size(max = 16)
        String rowLabel,

        @NotBlank
        @Size(max = 16)
        String seatNumber,

        Double xCoord,

        Double yCoord
) {
}

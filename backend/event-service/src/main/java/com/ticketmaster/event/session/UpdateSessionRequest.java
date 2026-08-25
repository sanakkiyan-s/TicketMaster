package com.ticketmaster.event.session;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record UpdateSessionRequest(

        @NotNull
        Instant startsAt,

        Instant endsAt,

        Instant onSaleAt
) {
}

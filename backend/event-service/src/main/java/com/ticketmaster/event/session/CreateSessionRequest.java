package com.ticketmaster.event.session;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateSessionRequest(

        @NotNull
        Instant startsAt,

        Instant endsAt,

        Instant onSaleAt
) {
}

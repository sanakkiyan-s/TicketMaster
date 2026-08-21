package com.ticketmaster.inventory.seat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * No pipeline exists yet that seeds a session's seat inventory from
 * venue-service's layout automatically (that's a real gap, noted in the
 * wiki) — an organizer/admin calls this explicitly once per session,
 * generating a grid of AVAILABLE seats per section. Idempotency: rejected
 * with 409 if the session already has seats (SeatInventoryService), so a
 * retry can't silently double-seed.
 */
public record SeedSessionRequest(

        @NotNull
        UUID eventId,

        @NotEmpty
        @Valid
        List<PriceTierSeed> priceTiers,

        @NotEmpty
        @Valid
        List<SectionSeed> sections
) {
    public record PriceTierSeed(
            @NotNull UUID id,
            @NotBlank @Size(max = 64) String label,
            @Min(0) int priceCents
    ) {
    }

    public record SectionSeed(
            @NotNull UUID id,
            @NotBlank @Size(max = 64) String name,
            @Min(1) @Max(200) int rows,
            @Min(1) @Max(100) int cols,
            @NotNull UUID priceTierId
    ) {
    }
}

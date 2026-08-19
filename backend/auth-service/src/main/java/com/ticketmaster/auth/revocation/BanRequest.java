package com.ticketmaster.auth.revocation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Validation runs at the edge, same convention as RegisterRequest. */
record BanRequest(

        @NotBlank
        @Size(max = 500)
        String reason
) {
}

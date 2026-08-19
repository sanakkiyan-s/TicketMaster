package com.ticketmaster.auth.registration;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validation runs at the edge of the service, before anything reaches the
 * domain (ECC input-validation rule). The password bound here is the only
 * place the plaintext exists in this service's memory; it is hashed
 * immediately and never stored, logged or returned.
 */
public record RegisterRequest(

        @NotBlank
        @Email
        @Size(max = 254)   // RFC 5321 maximum
        String email,

        @NotBlank
        @Size(min = 12, max = 128, message = "password must be between 12 and 128 characters")
        String password
) {
}

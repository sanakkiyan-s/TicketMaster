package com.ticketmaster.auth.login;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Note what is NOT here: no @Email, no minimum password length.
 *
 * Registration enforces the password policy; login must not restate it. A
 * login form that rejects an 8-character attempt with "password must be at
 * least 12 characters" tells an attacker the policy, and worse, a request that
 * fails validation with 400 instead of 401 distinguishes "malformed" from
 * "wrong" — which is a free oracle. Everything that fails here should fail
 * identically: 401.
 *
 * The @Size caps are DoS guards, not policy: without them a 10 MB "password"
 * reaches BCrypt, which is deliberately slow.
 */
record LoginRequest(

        @NotBlank
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(max = 128)
        String password
) {
}

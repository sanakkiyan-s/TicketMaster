package com.ticketmaster.auth.domain;

/**
 * Role names as stored in user_roles.role and later emitted in the JWT
 * `roles` claim that ADR-030's gateway gate and per-service ownership
 * checks read.
 *
 * Constants rather than an enum: the column is TEXT, and a role added by
 * a future migration must not require redeploying every service that
 * merely reads the claim.
 */
public final class Role {

    public static final String USER = "USER";
    public static final String ORGANIZER = "ORGANIZER";
    public static final String ADMIN = "ADMIN";

    private Role() {
    }
}

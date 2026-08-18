package com.ticketmaster.auth.jwt;

import java.util.List;

/**
 * Source of the signing key set.
 *
 * An interface because ADR-010 puts the real private key in Vault KV v2,
 * loaded into memory at startup and never written to disk — deliberately
 * NOT Vault Transit, since auth-service signs on every login and refresh
 * and a network round trip per signature is too expensive. That Vault
 * implementation is a separate slice; this seam keeps token minting from
 * having to change when it lands.
 *
 * The split between {@link #signing()} and {@link #published()} is ADR-012's
 * rotation, not decoration: during phase 1 a new key must be PUBLISHED in
 * JWKS for at least one gateway cache TTL before anything signs with it.
 * Collapsing the two into one method makes that phase unrepresentable, and
 * skipping it is the classic rotation failure — every warm-cached gateway
 * rejects every token for up to a full cache TTL.
 */
interface SigningKeyProvider {

    /** The single key new tokens are signed with right now. */
    SigningKey signing();

    /**
     * Every key gateways should accept, including {@link #signing()}.
     * Only public halves ever leave this service.
     */
    List<SigningKey> published();
}

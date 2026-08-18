package com.ticketmaster.auth.jwt;

/**
 * Where a key sits in ADR-012's rotation.
 *
 * The states exist so phase 1 is representable: a PUBLISHED key is in JWKS,
 * and gateways will accept tokens signed by it, but nothing signs with it yet.
 * That gap is the entire point — it guarantees every gateway has cached the
 * key BEFORE the first token signed by it exists. Without a distinct state,
 * "publish then cut over" collapses into "cut over", and every warm-cached
 * gateway rejects every token for up to one cache TTL.
 */
enum KeyStatus {

    /** In JWKS, not signing. Phase 1, and phase 3 for the outgoing key. */
    PUBLISHED,

    /** In JWKS and signing. Exactly one key may hold this. */
    SIGNING
}

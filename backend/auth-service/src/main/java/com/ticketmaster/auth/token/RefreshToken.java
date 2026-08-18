package com.ticketmaster.auth.token;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token row (ADR-012).
 *
 * The token itself is NOT here — only `tokenHash`. A database leak must not
 * hand an attacker usable refresh tokens, and a 30-day credential is worth
 * far more to a thief than a 10-minute access token.
 *
 * `usedAt` is set rather than the row being deleted, because a used row is
 * evidence: a second presentation of an already-exchanged token is the theft
 * signal that kills the whole family. Delete the row and replay becomes
 * indistinguishable from an unknown token.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    /**
     * Every rotation descended from one login shares this. Reuse detection
     * revokes by family, not by row — otherwise an attacker who replays a
     * stolen token only loses that one token and keeps the chain.
     */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** Per-device session, so one device can be logged out alone. */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
        // JPA
    }

    RefreshToken(UUID userId, String tokenHash, UUID familyId, UUID sessionId,
                 Instant issuedAt, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.sessionId = sessionId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }
}

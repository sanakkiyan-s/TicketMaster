package com.ticketmaster.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Lookup is by HASH, never by the token. The caller hashes first, so the
     * raw token never reaches a query parameter and cannot land in a
     * slow-query log or a JPA trace.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Claims a token for exactly one caller.
     *
     * `WHERE used_at IS NULL` is the whole mechanism. Reading used_at and then
     * writing it in two statements loses the race: two parallel refreshes both
     * read null, both rotate, and the family forks - two live chains from one
     * token, which is indistinguishable from theft. One conditional UPDATE
     * makes the database the arbiter; the loser gets 0 rows and knows it lost.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL")
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * ADR-012 reuse detection: revoke the whole family, not the one row.
     *
     * A replayed token means the attacker holds a token from this chain, and
     * possibly several. Revoking only the presented row leaves them holding
     * the rest, so the entire lineage dies - which does log the legitimate
     * user out. That is the intended trade: a forced re-login beats an
     * attacker keeping a 30-day credential.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}

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

    /**
     * ADR-012 self-service logout: revokes exactly the one device's active
     * token(s), keyed by `sid` rather than `familyId` - the two happen to
     * coincide today (one login = one family = one session, unchanged by
     * rotation), but the revocation endpoint's contract is "log out this
     * session," so it is expressed in those terms rather than borrowing
     * the family concept it is not actually about.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.sessionId = :sessionId AND t.revokedAt IS NULL")
    int revokeBySessionId(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    /**
     * ADR-012 "log out everywhere" / admin ban: every one of a user's
     * active refresh tokens across every family, in one statement. A bulk
     * update rather than looping {@link #revokeFamily} per family - the
     * end state (every active row for this user is revoked) is identical,
     * and this is the minimal addition the task asked for rather than a
     * new family-enumeration query this repository does not otherwise need.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}

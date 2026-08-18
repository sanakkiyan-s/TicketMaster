package com.ticketmaster.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Lookup is by HASH, never by the token. The caller hashes first, so the
     * raw token never reaches a query parameter and cannot land in a slow-query
     * log or a JPA trace.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}

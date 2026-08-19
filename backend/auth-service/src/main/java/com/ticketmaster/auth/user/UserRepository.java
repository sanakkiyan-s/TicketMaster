package com.ticketmaster.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Native query with an explicit CITEXT cast — NOT a derived
     * findByEmail(), and not a stylistic choice.
     *
     * Hibernate binds a String parameter as varchar. Postgres then
     * resolves `citext = varchar` under text semantics, i.e.
     * case-SENSITIVE, so a derived query silently fails to find
     * "Case@Example.com" when asked for "CASE@EXAMPLE.COM". The column
     * being CITEXT is not enough on its own.
     *
     * This asymmetry is the dangerous part: the unique index still
     * rejects a duplicate signup (index comparisons stay case-insensitive),
     * so registration looks correct while lookup quietly misses — which
     * would lock a user out of login for typing their own address with
     * different capitalisation.
     *
     * Casting the parameter puts both operands in citext and restores the
     * case-insensitive comparison the column type promises.
     */
    @Query(value = "SELECT * FROM users WHERE email = cast(:email AS citext)", nativeQuery = true)
    Optional<User> findByEmail(@Param("email") String email);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM users WHERE email = cast(:email AS citext))",
            nativeQuery = true)
    boolean existsByEmail(@Param("email") String email);
}

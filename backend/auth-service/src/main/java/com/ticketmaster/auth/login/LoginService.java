package com.ticketmaster.auth.login;

import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class LoginService {

    /**
     * A real BCrypt hash of a value nobody knows, verified against when the
     * email does not exist.
     *
     * Without this, an unknown email returns as soon as the lookup misses,
     * while a known email pays ~250ms of BCrypt. That timing difference is
     * measurable over the network and reveals which addresses have accounts —
     * the exact enumeration that returning an identical 401 was meant to
     * prevent. Constant-ish work on both paths closes it.
     *
     * Strength must match SecurityConfig's encoder (12), or the dummy costs a
     * different amount of time than the real thing and the leak reopens.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.CjOnMzCwlZbTXsF5Dg8vJKZHNQZ0nBu";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter attemptLimiter;
    private final LoginSecurityProperties security;

    LoginService(UserRepository users, PasswordEncoder passwordEncoder,
                 LoginAttemptLimiter attemptLimiter, LoginSecurityProperties security) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.attemptLimiter = attemptLimiter;
        this.security = security;
    }

    /**
     * Not readOnly: a tripped attempt or a successful login writes back
     * to the user's lockedUntil (see User#lock, User#unlock), persisted
     * via JPA dirty checking on commit.
     *
     * noRollbackFor is load-bearing, not optional (ADR-040 keeps this
     * from the single-window design it replaces): the wrong-password
     * branch below can mutate the user (lock) and THEN throw
     * InvalidCredentialsException to fail the request. Spring's default
     * rollback rule reverts a transaction on any unchecked exception,
     * which would silently discard that exact mutation - the DB-backed
     * lock would never actually persist, while looking correct in every
     * code review because the response is still a normal 401.
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    User authenticate(String email, String rawPassword) {
        // Checked before touching the DB at all - a username already
        // over its Redis budget shouldn't cost a lookup or a BCrypt call.
        if (attemptLimiter.isBlocked(email)) {
            throw new TooManyLoginAttemptsException();
        }

        Optional<User> found = users.findByEmail(email);
        Instant now = Instant.now();

        if (found.isEmpty()) {
            // Burn the same work, then fail the same way. No user row to
            // lock regardless of the Redis windows' state - enumeration
            // safety only requires the counter behave identically, not
            // that a lock actually exist to trip.
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            attemptLimiter.recordFailure(email);
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        if (user.isLocked(now)) {
            // Same BCrypt cost and same exception as a wrong password -
            // a locked account must not be distinguishable from one that
            // simply got the password wrong, by timing or by response.
            passwordEncoder.matches(rawPassword, user.getPasswordHash());
            attemptLimiter.recordFailure(email);
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // recordFailure returns true only on the ONE attempt that
            // just crossed a Redis threshold - a single DB write per
            // lock cycle, not one per failed attempt (ADR-040).
            if (attemptLimiter.recordFailure(email)) {
                user.lock(now, security.lockDuration());
            }
            throw new InvalidCredentialsException();
        }

        user.unlock(now);
        attemptLimiter.reset(email);
        return user;
    }
}

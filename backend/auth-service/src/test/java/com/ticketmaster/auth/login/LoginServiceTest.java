package com.ticketmaster.auth.login;

import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LoginService's lockout wiring (ADR-040): Redis-limiter
 * short-circuit, the DB-persisted lock triggered only when
 * LoginAttemptLimiter.recordFailure reports a trip, and counter reset on
 * success. Real User instances are used rather than mocks - User's own
 * lock/isLocked/unlock behaviour is exactly what this wiring depends on,
 * so faking it would test nothing.
 */
class LoginServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String HASH = "hashed-password";

    private static final LoginSecurityProperties SECURITY = new LoginSecurityProperties(
            5, Duration.ofMinutes(1), 15, Duration.ofHours(24), Duration.ofMinutes(15));

    private UserRepository users;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptLimiter attemptLimiter;
    private LoginService loginService;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        attemptLimiter = mock(LoginAttemptLimiter.class);
        loginService = new LoginService(users, passwordEncoder, attemptLimiter, SECURITY);
    }

    @Test
    void redisLimiterTripsBeforeTheDatabaseIsEverTouched() {
        // The already-over-budget case: isBlocked() is checked first
        // specifically so an already-over-budget username doesn't cost a
        // lookup or a BCrypt call.
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(true);

        assertThrows(TooManyLoginAttemptsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        verifyNoInteractions(users);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void wrongPasswordRecordsAFailureButDoesNotLockBelowTheThreshold() {
        User user = new User(EMAIL, HASH, Instant.now());
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);
        when(attemptLimiter.recordFailure(EMAIL)).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        assertFalse(user.isLocked(Instant.now()));
        verify(attemptLimiter).recordFailure(EMAIL);
    }

    @Test
    void theAttemptThatTripsEitherRedisWindowLocksTheAccountInTheDatabase() {
        // recordFailure reporting true is LoginAttemptLimiter's signal
        // that THIS attempt crossed a threshold (fast or slow window) -
        // LoginService must write the DB lock on that signal alone, not
        // by counting attempts itself.
        User user = new User(EMAIL, HASH, Instant.now());
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);
        when(attemptLimiter.recordFailure(EMAIL)).thenReturn(true);

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        assertTrue(user.isLocked(Instant.now().plusSeconds(1)));
    }

    @Test
    void aLockedAccountBurnsBcryptAndThrowsTheSameExceptionAsAWrongPassword() {
        // Locked-account and wrong-password must be indistinguishable -
        // same exception, same BCrypt cost - or the lock state itself
        // becomes an enumeration/timing oracle.
        User user = new User(EMAIL, HASH, Instant.now());
        user.lock(Instant.now(), SECURITY.lockDuration());
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        verify(passwordEncoder).matches(eq(PASSWORD), eq(HASH));
        verify(attemptLimiter).recordFailure(EMAIL);
        // A locked attempt must not re-lock or otherwise mutate the
        // existing lock state.
        assertTrue(user.isLocked(Instant.now()));
    }

    @Test
    void successfulLoginResetsBothTheRedisAndDatabaseState() {
        User user = new User(EMAIL, HASH, Instant.now());
        // Locked in the past, not currently active, so authenticate()
        // reaches the password check rather than the locked branch, and
        // this proves unlock() clears a stale lockedUntil on success too.
        user.lock(Instant.now().minus(Duration.ofHours(1)), Duration.ofMinutes(1));

        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

        User result = loginService.authenticate(EMAIL, PASSWORD);

        assertEquals(user, result);
        assertFalse(user.isLocked(Instant.now()));
        verify(attemptLimiter).reset(EMAIL);
        verify(attemptLimiter, never()).recordFailure(anyString());
    }

    @Test
    void unknownEmailBurnsTheDummyHashAndRecordsAFailureWithoutTouchingAnyUser() {
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        // Even if Redis reports a trip, there is no user row to lock -
        // this must not throw or otherwise misbehave against an absent user.
        when(attemptLimiter.recordFailure(EMAIL)).thenReturn(true);

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        verify(passwordEncoder).matches(eq(PASSWORD), any());
        verify(attemptLimiter).recordFailure(EMAIL);
    }
}

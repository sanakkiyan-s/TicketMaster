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
 * Unit tests for the new lockout wiring in LoginService: Redis-limiter
 * short-circuit, DB-persisted lock threshold, and counter reset on
 * success. Real User instances are used rather than mocks - User's own
 * recordFailedLogin/isLocked/resetFailedLogins behaviour is exactly what
 * this wiring depends on, so faking it would test nothing.
 */
class LoginServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String HASH = "hashed-password";

    private static final LoginSecurityProperties SECURITY =
            new LoginSecurityProperties(5, Duration.ofMinutes(1), 10, Duration.ofMinutes(15));

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
        // The 5th-failure case: isBlocked() is checked first specifically
        // so an already-over-budget username doesn't cost a lookup or a
        // BCrypt call.
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(true);

        assertThrows(TooManyLoginAttemptsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        verifyNoInteractions(users);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void wrongPasswordRecordsAFailureAgainstBothCounters() {
        User user = new User(EMAIL, HASH, Instant.now());
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        assertEquals(1, user.getFailedLoginAttempts());
        verify(attemptLimiter).recordFailure(EMAIL);
    }

    @Test
    void the10thFailureTripsTheDatabaseLockEvenIfRedisWouldHaveAllowedIt() {
        // Redis forgets a failure once its window expires; the DB
        // backstop must not, so it is exercised here with the Redis
        // limiter stubbed to always allow through - exactly the attacker
        // who paces guesses to dodge the Redis window.
        User user = new User(EMAIL, HASH, Instant.now());
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

        for (int i = 0; i < 9; i++) {
            assertThrows(InvalidCredentialsException.class,
                    () -> loginService.authenticate(EMAIL, PASSWORD));
        }
        assertFalse(user.isLocked(Instant.now()), "locked before the threshold was reached");

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        assertEquals(10, user.getFailedLoginAttempts());
        assertTrue(user.isLocked(Instant.now().plusSeconds(1)));
    }

    @Test
    void aLockedAccountBurnsBcryptAndThrowsTheSameExceptionAsAWrongPassword() {
        // Locked-account and wrong-password must be indistinguishable -
        // same exception, same BCrypt cost - or the lock state itself
        // becomes an enumeration/timing oracle.
        User user = new User(EMAIL, HASH, Instant.now());
        for (int i = 0; i < 10; i++) {
            user.recordFailedLogin(Instant.now(), SECURITY.lockThreshold(), SECURITY.lockDuration());
        }
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        verify(passwordEncoder).matches(eq(PASSWORD), eq(HASH));
        verify(attemptLimiter).recordFailure(EMAIL);
        // A locked attempt must not fall through to user.recordFailedLogin -
        // the count must stay exactly at what already tripped the lock.
        assertEquals(10, user.getFailedLoginAttempts());
    }

    @Test
    void successfulLoginResetsBothTheRedisAndDatabaseCounters() {
        User user = new User(EMAIL, HASH, Instant.now());
        user.recordFailedLogin(Instant.now(), SECURITY.lockThreshold(), SECURITY.lockDuration());
        user.recordFailedLogin(Instant.now(), SECURITY.lockThreshold(), SECURITY.lockDuration());

        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

        User result = loginService.authenticate(EMAIL, PASSWORD);

        assertEquals(user, result);
        assertEquals(0, user.getFailedLoginAttempts());
        assertFalse(user.isLocked(Instant.now()));
        verify(attemptLimiter).reset(EMAIL);
        verify(attemptLimiter, never()).recordFailure(anyString());
    }

    @Test
    void unknownEmailBurnsTheDummyHashAndRecordsAFailureWithoutTouchingAnyUser() {
        when(attemptLimiter.isBlocked(EMAIL)).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> loginService.authenticate(EMAIL, PASSWORD));

        verify(passwordEncoder).matches(eq(PASSWORD), any());
        verify(attemptLimiter).recordFailure(EMAIL);
    }
}

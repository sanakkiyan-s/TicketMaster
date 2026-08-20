package com.ticketmaster.auth.login;

/**
 * Thrown when LoginAttemptLimiter's per-username Redis window has already
 * tripped. Unlike InvalidCredentialsException, this is safe to answer
 * distinctly (429, not 401): the counter increments identically for a
 * known and an unknown email, so seeing this response confirms only "this
 * username was guessed at repeatedly," never "this username has an
 * account" - the enumeration protection InvalidCredentialsException exists
 * for is unaffected.
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    public TooManyLoginAttemptsException() {
        super("too many login attempts");
    }
}

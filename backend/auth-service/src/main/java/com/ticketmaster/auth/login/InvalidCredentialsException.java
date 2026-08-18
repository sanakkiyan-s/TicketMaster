package com.ticketmaster.auth.login;

/**
 * Thrown for BOTH unknown email and wrong password, carrying no detail about
 * which. Anything that distinguishes the two turns the login endpoint into an
 * account-enumeration oracle: an attacker learns which addresses hold accounts
 * without ever guessing a password.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}

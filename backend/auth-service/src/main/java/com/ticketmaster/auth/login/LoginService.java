package com.ticketmaster.auth.login;

import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    LoginService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    User authenticate(String email, String rawPassword) {
        Optional<User> found = users.findByEmail(email);

        if (found.isEmpty()) {
            // Burn the same work, then fail the same way.
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return user;
    }
}

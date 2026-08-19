package com.ticketmaster.auth.registration;

import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
class RegistrationService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    RegistrationService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    User register(String email, String rawPassword) {
        // Cheap pre-check purely so the common case returns a clean 409
        // without burning a hash and a failed INSERT. It is NOT the
        // correctness mechanism: two concurrent registrations for the same
        // address both pass this check. The unique constraint on
        // users.email is what actually decides, and the catch below turns
        // its violation into the same 409.
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException("email already registered");
        }

        Instant now = Instant.now(clock);
        User user = new User(email, passwordEncoder.encode(rawPassword), now);

        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent registration.
            throw new EmailAlreadyRegisteredException("email already registered");
        }
    }
}

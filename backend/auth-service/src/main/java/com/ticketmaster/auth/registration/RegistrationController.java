package com.ticketmaster.auth.registration;

import com.ticketmaster.auth.user.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * ADR-034 versions the REST edge at /api/v1. The gateway routes that
 * prefix through unchanged, so the service exposes the same path it is
 * reached by — no rewriting, and a path in a log means the same thing on
 * both sides of the gateway.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = registrationService.register(request.email(), request.password());

        RegisterResponse body = new RegisterResponse(user.getId(), user.getEmail(), user.getRoles());
        return ResponseEntity
                .created(URI.create("/api/v1/users/" + user.getId()))
                .body(body);
    }
}

package com.ticketmaster.auth.api;

import com.ticketmaster.auth.domain.User;
import com.ticketmaster.auth.service.EmailAlreadyRegisteredException;
import com.ticketmaster.auth.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    /**
     * 409, not 400: the request is well-formed, it conflicts with existing
     * state.
     *
     * This does leak that an address is registered. That is a deliberate,
     * bounded trade — a registration form cannot avoid it without silently
     * accepting duplicate signups, which breaks the user far worse. The
     * enumeration surface is closed elsewhere: login must answer
     * identically for unknown-user and wrong-password, and ADR-014's rate
     * limiting bounds how fast this endpoint can be probed at all.
     */
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<Void> handleDuplicate() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}

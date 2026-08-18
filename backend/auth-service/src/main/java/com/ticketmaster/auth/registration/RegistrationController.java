package com.ticketmaster.auth.registration;

import com.ticketmaster.auth.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "auth", description = "Registration, login and token lifecycle")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @Operation(
            summary = "Register a new account",
            description = """
                    Creates the account only. No token is issued — see
                    POST /api/v1/auth/login. Email comparison is
                    case-insensitive, so User@x.com and user@x.com are the
                    same account and the second attempt is a 409.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            // Documented explicitly because the error shape is part of the
            // contract (ADR-037: one ProblemDetail shape, RFC 9457).
            // Without this springdoc would advertise only the 201 and
            // clients would have to discover the failure modes by hitting
            // them.
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Email already registered",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = registrationService.register(request.email(), request.password());

        RegisterResponse body = new RegisterResponse(user.getId(), user.getEmail(), user.getRoles());
        return ResponseEntity
                .created(URI.create("/api/v1/users/" + user.getId()))
                .body(body);
    }
}

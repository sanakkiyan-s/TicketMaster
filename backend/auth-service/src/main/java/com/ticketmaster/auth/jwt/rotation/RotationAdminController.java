package com.ticketmaster.auth.jwt.rotation;

import com.ticketmaster.auth.jwt.ForbiddenException;
import com.ticketmaster.auth.jwt.TokenVerifier;
import com.ticketmaster.auth.jwt.VerifiedToken;
import com.ticketmaster.auth.shared.AdminActionAuditLogger;
import com.ticketmaster.auth.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-012's admin-triggered rotation controls, ADR-030-gated to ADMIN.
 *
 * Bearer tokens here are verified by {@link TokenVerifier} directly, not by
 * Spring Security's filter chain (SecurityConfig permits these routes and
 * leaves authentication to this class) - this service holds the actual
 * signing key material, so it checks the signature itself rather than
 * trusting an unverified decode the way a downstream service might.
 */
@RestController
@RequestMapping("/api/v1/auth/admin/keys")
@Tag(name = "admin-keys", description = "ADR-012 key rotation controls (ADMIN only)")
class RotationAdminController {

    private final TokenVerifier tokenVerifier;
    private final RotationOrchestrator orchestrator;
    private final AdminActionAuditLogger audit;

    RotationAdminController(TokenVerifier tokenVerifier, RotationOrchestrator orchestrator,
                             AdminActionAuditLogger audit) {
        this.tokenVerifier = tokenVerifier;
        this.orchestrator = orchestrator;
        this.audit = audit;
    }

    @Operation(summary = "Start a key rotation now",
            description = "Begins ADR-012 phase 1 (PUBLISH), outside the normal 90-day schedule.")
    @PostMapping("/rotate")
    ResponseEntity<Void> rotate(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        VerifiedToken admin = requireAdmin(authorization);
        orchestrator.startRotation();
        audit.record(admin.userId().toString(), "KEY_ROTATION_STARTED", "auth-service:signing-keys", null);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Compromise response: skip straight to phase 4",
            description = "Destroys the current signing key's private material immediately. Every live "
                    + "access token dies; refresh tokens are unaffected since they are opaque.")
    @PostMapping("/compromise")
    ResponseEntity<Void> compromise(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        VerifiedToken admin = requireAdmin(authorization);
        orchestrator.handleCompromise();
        audit.record(admin.userId().toString(), "KEY_COMPROMISE_RESPONSE", "auth-service:signing-keys", null);
        return ResponseEntity.noContent().build();
    }

    private VerifiedToken requireAdmin(String authorization) {
        VerifiedToken token = tokenVerifier.verify(authorization);
        if (!token.roles().contains(Role.ADMIN)) {
            throw new ForbiddenException();
        }
        return token;
    }
}

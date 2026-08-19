package com.ticketmaster.auth.revocation;

import com.ticketmaster.auth.jwt.ForbiddenException;
import com.ticketmaster.auth.jwt.TokenVerifier;
import com.ticketmaster.auth.jwt.VerifiedToken;
import com.ticketmaster.auth.shared.AdminActionAuditLogger;
import com.ticketmaster.auth.shared.outbox.RevocationPublisher;
import com.ticketmaster.auth.token.RefreshTokenService;
import com.ticketmaster.auth.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** ADR-030 admin-only user ban, revoking via ADR-012's outbox. */
@RestController
@RequestMapping("/api/v1/auth/admin/users")
@Tag(name = "admin-users", description = "Admin user management (ADMIN only)")
class AdminUserController {

    private final TokenVerifier tokenVerifier;
    private final RevocationPublisher revocationPublisher;
    private final RefreshTokenService refreshTokens;
    private final AdminActionAuditLogger audit;
    private final Clock clock;

    AdminUserController(TokenVerifier tokenVerifier, RevocationPublisher revocationPublisher,
                         RefreshTokenService refreshTokens, AdminActionAuditLogger audit, Clock clock) {
        this.tokenVerifier = tokenVerifier;
        this.revocationPublisher = revocationPublisher;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Operation(summary = "Ban a user",
            description = "Revokes every session the user has, platform-wide, and every refresh "
                    + "token family. ADR-030: admin actions skip ownership checks by design.")
    @PostMapping("/{id}/ban")
    @Transactional
    ResponseEntity<Void> ban(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                              @PathVariable UUID id,
                              @Valid @RequestBody BanRequest request) {
        VerifiedToken admin = tokenVerifier.verify(authorization);
        if (!admin.roles().contains(Role.ADMIN)) {
            throw new ForbiddenException();
        }

        Instant now = Instant.now(clock);
        revocationPublisher.publishRevocation("user:" + id, now, request.reason());
        refreshTokens.revokeAllForUser(id);

        audit.record(admin.userId().toString(), "USER_BANNED", "user:" + id, request.reason());
        return ResponseEntity.noContent().build();
    }
}

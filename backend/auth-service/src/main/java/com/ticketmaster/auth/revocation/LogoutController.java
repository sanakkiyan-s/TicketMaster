package com.ticketmaster.auth.revocation;

import com.ticketmaster.auth.jwt.TokenVerifier;
import com.ticketmaster.auth.jwt.VerifiedToken;
import com.ticketmaster.auth.shared.outbox.RevocationPublisher;
import com.ticketmaster.auth.token.RefreshCookie;
import com.ticketmaster.auth.token.RefreshTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * Self-service revocation (ADR-012). Bearer tokens are verified by {@link
 * TokenVerifier} directly - see its javadoc for why this service checks its
 * own signature rather than trusting an unverified decode.
 *
 * Both endpoints write one outbox row and revoke Postgres-side refresh
 * tokens in the SAME transaction as each other (ADR-007's whole point):
 * either both persist or neither does.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Registration, login and token lifecycle")
class LogoutController {

    private final TokenVerifier tokenVerifier;
    private final RevocationPublisher revocationPublisher;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookie refreshCookie;
    private final Clock clock;

    LogoutController(TokenVerifier tokenVerifier, RevocationPublisher revocationPublisher,
                      RefreshTokenService refreshTokens, RefreshCookie refreshCookie, Clock clock) {
        this.tokenVerifier = tokenVerifier;
        this.revocationPublisher = revocationPublisher;
        this.refreshTokens = refreshTokens;
        this.refreshCookie = refreshCookie;
        this.clock = clock;
    }

    @Operation(summary = "Log out the current device",
            description = "Revokes only the calling session (`sid` claim of the presented access "
                    + "token). Other devices stay logged in. Clears the refresh cookie.")
    @PostMapping("/logout")
    @Transactional
    ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        VerifiedToken token = tokenVerifier.verify(authorization);
        Instant now = Instant.now(clock);

        revocationPublisher.publishRevocation("session:" + token.sessionId(), now, "user logout");
        refreshTokens.revokeSession(token.sessionId());

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.expire().toString())
                .build();
    }

    @Operation(summary = "Log out every device",
            description = "Revokes every session the calling user has, on every device. Clears the "
                    + "refresh cookie for the device that called this endpoint.")
    @PostMapping("/logout-everywhere")
    @Transactional
    ResponseEntity<Void> logoutEverywhere(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        VerifiedToken token = tokenVerifier.verify(authorization);
        Instant now = Instant.now(clock);

        revocationPublisher.publishRevocation("user:" + token.userId(), now, "logout everywhere");
        refreshTokens.revokeAllForUser(token.userId());

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.expire().toString())
                .build();
    }
}

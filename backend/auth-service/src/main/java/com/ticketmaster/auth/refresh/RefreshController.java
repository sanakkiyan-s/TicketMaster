package com.ticketmaster.auth.refresh;

import com.ticketmaster.auth.jwt.TokenMinting;
import com.ticketmaster.auth.token.InvalidRefreshTokenException;
import com.ticketmaster.auth.token.RefreshCookie;
import com.ticketmaster.auth.token.RefreshTokenService;
import com.ticketmaster.auth.token.RotatedSession;
import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Registration, login and token lifecycle")
class RefreshController {

    private final RefreshTokenService refreshTokens;
    private final TokenMinting tokens;
    private final UserRepository users;
    private final RefreshCookie refreshCookie;

    RefreshController(RefreshTokenService refreshTokens, TokenMinting tokens,
                      UserRepository users, RefreshCookie refreshCookie) {
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.users = users;
        this.refreshCookie = refreshCookie;
    }

    @Operation(
            summary = "Exchange a refresh token for a new token pair",
            description = """
                    Takes no request body. The refresh token is read from the
                    httpOnly cookie the browser replays automatically, which is
                    why JavaScript never needs to hold it.

                    The presented token is consumed: refresh tokens are
                    single-use, and presenting one twice is treated as theft.
                    That revokes every token in the family, so the legitimate
                    user is signed out too - the intended trade against an
                    attacker keeping a 30-day credential.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rotated"),
            @ApiResponse(responseCode = "401",
                    description = "Unknown, expired, revoked or replayed token - the response "
                            + "does not distinguish them",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/refresh")
    ResponseEntity<RefreshResponse> refresh(
            @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {

        if (refreshToken == null || refreshToken.isBlank()) {
            // Same 401 as a bad token. A distinct "no cookie" response would
            // tell a probe whether it is talking to a browser with a session.
            throw new InvalidRefreshTokenException("no refresh cookie presented");
        }

        RotatedSession rotated = refreshTokens.rotate(refreshToken);

        // The roles claim is re-read from the database on every refresh rather
        // than copied from the old token. That is what bounds privilege
        // staleness to one access-token lifetime: an admin demoted five
        // minutes ago stops being an admin at their next refresh, without any
        // revocation machinery.
        User user = users.findById(rotated.userId())
                .orElseThrow(() -> new InvalidRefreshTokenException("token references a deleted user"));

        String accessToken = tokens.issueAccessToken(user.getId(), rotated.sessionId(), user.getRoles());

        RefreshResponse body = new RefreshResponse(
                accessToken, "Bearer", tokens.accessTokenLifetime().toSeconds());

        Duration cookieLifetime =
                Duration.between(Instant.now(), rotated.refreshToken().expiresAt());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie.build(rotated.refreshToken(), cookieLifetime).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}

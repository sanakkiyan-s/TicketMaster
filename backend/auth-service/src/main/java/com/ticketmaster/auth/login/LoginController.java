package com.ticketmaster.auth.login;

import com.ticketmaster.auth.jwt.TokenMinting;
import com.ticketmaster.auth.token.IssuedRefreshToken;
import com.ticketmaster.auth.token.RefreshTokenService;
import com.ticketmaster.auth.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Registration, login and token lifecycle")
class LoginController {

    private final LoginService loginService;
    private final TokenMinting tokens;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookie refreshCookie;

    LoginController(LoginService loginService, TokenMinting tokens,
                    RefreshTokenService refreshTokens, RefreshCookie refreshCookie) {
        this.loginService = loginService;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.refreshCookie = refreshCookie;
    }

    @Operation(
            summary = "Exchange credentials for tokens",
            description = """
                    Returns a 10-minute access token in the body and a 30-day
                    refresh token as an httpOnly cookie, which JavaScript
                    cannot read.

                    A wrong password and an unknown email produce the SAME 401
                    with the same body, deliberately: any difference between
                    them lets an attacker enumerate which addresses have
                    accounts.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = loginService.authenticate(request.email(), request.password());

        IssuedRefreshToken refresh = refreshTokens.issueForNewSession(user.getId());
        String accessToken = tokens.issueAccessToken(
                user.getId(), refresh.sessionId(), user.getRoles());

        LoginResponse body = new LoginResponse(
                accessToken,
                "Bearer",
                tokens.accessTokenLifetime().toSeconds(),
                new LoginResponse.UserSummary(user.getId(), user.getEmail(), user.getRoles()));

        Duration cookieLifetime = Duration.between(java.time.Instant.now(), refresh.expiresAt());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.build(refresh, cookieLifetime).toString())
                // The access token must never be cached by a proxy or written
                // into a shared cache keyed only by URL.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}

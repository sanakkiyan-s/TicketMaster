package com.ticketmaster.auth.token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the Set-Cookie for the refresh token. Every attribute here is a
 * security control, not a formality:
 *
 *   HttpOnly  — JavaScript cannot read it, so an XSS cannot steal a 30-day
 *               credential. This is the whole reason the refresh token is a
 *               cookie instead of a JSON field.
 *   Secure    — never sent over plaintext HTTP. Configurable ONLY because
 *               local dev has no TLS; production must leave it true.
 *   SameSite=Strict — the browser will not attach it to cross-site requests,
 *               which is what makes CSRF on the refresh endpoint a non-issue
 *               even though the service is otherwise CSRF-disabled.
 *   Path      — scoped to the auth endpoints, so it is not attached to every
 *               unrelated API call and cannot leak through a chatty log on
 *               some other route.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "refresh_token";

    private final boolean secure;

    RefreshCookie(@Value("${auth.refresh-cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie build(IssuedRefreshToken token, Duration lifetime) {
        return ResponseCookie.from(NAME, token.rawToken())
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(lifetime)
                .build();
    }

    /**
     * An already-expired Set-Cookie, for logout. Same attributes as {@link
     * #build}, with an empty value and {@code maxAge(0)}, which is what
     * tells the browser to delete the cookie immediately rather than merely
     * not renew it.
     */
    public ResponseCookie expire() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(Duration.ZERO)
                .build();
    }
}

package com.ticketmaster.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param enabled A kill switch, checked before Redis is touched at all. Local
 *                dev or a Redis outage should not require deleting config to
 *                get unblocked - see RateLimitFilter's fail-open note for why
 *                a Redis failure at RUNTIME is handled separately from this.
 * @param rules   Checked in order; the first prefix match wins. A path
 *                matching no rule is not rate-limited by this filter at all -
 *                the JwtAuthenticationFilter's normal 401 handling still
 *                applies to anything that requires a token.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
record RateLimitProperties(boolean enabled, List<RateLimitRule> rules) {
}

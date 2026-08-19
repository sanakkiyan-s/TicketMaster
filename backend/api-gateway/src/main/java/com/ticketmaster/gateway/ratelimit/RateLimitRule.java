package com.ticketmaster.gateway.ratelimit;

import java.time.Duration;

/**
 * One throttle: at most {@code limit} requests to a path starting with
 * {@code pathPrefix} per {@code window}, per client IP.
 *
 * A record rather than a Map entry so config typos surface at startup -
 * Spring's relaxed binding fails to bind a malformed YAML block immediately,
 * instead of the mistake surfacing later as "why is login unthrottled".
 */
record RateLimitRule(String pathPrefix, int limit, Duration window) {
}

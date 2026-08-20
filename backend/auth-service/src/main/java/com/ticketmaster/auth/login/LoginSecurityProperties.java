package com.ticketmaster.auth.login;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * ADR-040's two-window Redis login limiter. Replaces the old single
 * window + DB-persisted {@code failed_login_attempts} counter: that
 * column never decayed (only a successful login cleared it), so an
 * infrequent mistyper could accumulate toward a lock across attempts
 * weeks apart. Both Redis windows self-decay via TTL instead.
 *
 * @param fastWindowLimit failed logins for one username allowed within
 *                        {@code fastWindowTtl} before LoginAttemptLimiter
 *                        starts rejecting outright - burst protection.
 * @param fastWindowTtl   the fast window's length.
 * @param slowWindowLimit failed logins for one username allowed within
 *                        {@code slowWindowTtl} - catches an attacker
 *                        pacing guesses just under the fast window's
 *                        rate to dodge it. Deliberately much longer than
 *                        the fast window, so it survives many separate
 *                        fast windows resetting.
 * @param slowWindowTtl   the slow window's length.
 * @param lockDuration    how long the DB-persisted lock lasts once
 *                        either window trips - see User#lock.
 */
@ConfigurationProperties(prefix = "auth.login-security")
record LoginSecurityProperties(
        int fastWindowLimit,
        Duration fastWindowTtl,
        int slowWindowLimit,
        Duration slowWindowTtl,
        Duration lockDuration) {
}

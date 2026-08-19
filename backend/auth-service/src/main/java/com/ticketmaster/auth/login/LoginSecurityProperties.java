package com.ticketmaster.auth.login;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param redisMaxAttempts failed logins for one username allowed within
 *                          {@code redisWindow} before LoginAttemptLimiter
 *                          starts rejecting outright.
 * @param redisWindow       the sliding failure-count window.
 * @param lockThreshold      failed attempts (persisted on the user row,
 *                           surviving across Redis windows) before the
 *                           account itself locks - see User#recordFailedLogin.
 * @param lockDuration       how long a lock lasts once tripped.
 */
@ConfigurationProperties(prefix = "auth.login-security")
record LoginSecurityProperties(
        int redisMaxAttempts,
        Duration redisWindow,
        int lockThreshold,
        Duration lockDuration) {
}

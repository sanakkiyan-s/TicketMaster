package com.ticketmaster.venue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Injected rather than called statically so tests can freeze time — same
 * reasoning as every other service's identical ClockConfig. Timestamps are
 * application-supplied, never SQL now() (ADR-002).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

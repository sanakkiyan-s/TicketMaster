package com.ticketmaster.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Injected rather than called statically so tests can freeze time, and so
 * hold-expiry decisions are computed from one consistent clock instance
 * across many inventory-service replicas rather than SQL {@code now()}
 * (ADR-002's clock-skew amendment).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

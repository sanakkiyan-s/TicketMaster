package com.ticketmaster.event;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Events, sessions/shows, artist/performer data, and organizer CRUD
 * (wiki/projects/event-service.md's Target Design, ADR-036 Phase 2).
 *
 * Deliberately absent: media-service trailer metadata (media-service does
 * not exist yet, ADR-017 is Phase 5), any real venue-service integration
 * beyond a bare venue_id reference (venue-service is being built alongside
 * this one, not yet callable), any gRPC surface (ADR-023 covers that
 * later; this slice needs no internal calls yet).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EventApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventApplication.class, args);
    }
}

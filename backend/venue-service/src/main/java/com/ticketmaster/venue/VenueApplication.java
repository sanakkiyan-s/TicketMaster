package com.ticketmaster.venue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Venues and their seating layouts - sections and seats
 * (wiki/projects/venue-service.md's Target Design, ADR-036 Phase 2). A
 * venue's seat map is reused across many events at that venue, which is why
 * this is a separate service from event-service rather than a table on it -
 * layout changes don't couple to event lifecycle.
 *
 * Deliberately absent: any real inventory-service integration beyond being
 * the seat map inventory-service will later read via API when provisioning
 * per-session inventory (inventory-service does not exist yet), any gRPC
 * surface (ADR-023 covers that later; this slice needs no internal calls
 * yet).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VenueApplication {

    public static void main(String[] args) {
        SpringApplication.run(VenueApplication.class, args);
    }
}

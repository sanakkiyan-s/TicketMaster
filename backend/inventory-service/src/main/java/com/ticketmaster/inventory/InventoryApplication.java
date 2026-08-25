package com.ticketmaster.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ADR-002's concurrency core: seat hold/confirm state machine. Deliberately
 * absent: any real integration with booking-service (doesn't exist yet,
 * ADR-036 Phase 3 builds it right after this), any Resilience4j circuit
 * breaker on the Redis fast-gate (SeatLockGate's javadoc explains why this
 * is a noted follow-up, not an oversight).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}

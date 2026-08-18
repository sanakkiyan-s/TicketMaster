// Lets Gradle auto-provision the Java 21 toolchain the root build asks
// for, on machines that don't already have that exact JDK installed.
plugins {
    // 1.x is the Gradle 9 compatible line — 0.8.0 references
    // JvmVendorSpec.IBM_SEMERU, which Gradle 9 removed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ticketmaster"

include(
    "backend:api-gateway",
    "backend:auth-service",
    "backend:user-service",
    "backend:event-service",
    "backend:venue-service",
    "backend:search-service",
    "backend:inventory-service",
    "backend:booking-service",
    "backend:queue-service",
    "backend:payment-service",
    "backend:ticket-service",
    "backend:notification-service",
    "backend:fraud-service",
    "backend:analytics-service",
    "backend:media-service"
)

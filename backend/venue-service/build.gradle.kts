// venue-service: venues, seating layout (ADR-036 Phase 2).
dependencies {
}

// First-and-only @SpringBootApplication main class in this module, so
// bootJar is re-enabled and the plain jar disabled — same pattern as
// event-service's build.gradle.kts (the root build turns bootJar off by
// default for every module until it actually has an entry point).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = false
}

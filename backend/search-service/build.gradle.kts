// search-service: Kafka-fed, read-optimized Elasticsearch projection over
// event.*/venue.* (ADR-036 Phase 2). Consumer only — no outbox, no
// Postgres/Flyway. Root build.gradle.kts's subprojects{} block still adds
// data-jpa/postgres/flyway to every module including this one; they go
// unused here rather than the shared root build carving out a per-module
// exception for one service.
dependencies {
    // Spring Data Elasticsearch (repository-style access), not the
    // low-level RestHighLevelClient — matches this repo's JPA-repository
    // convention in every other service.
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("org.springframework.kafka:spring-kafka")

    // event.*/venue.* outbox messages are plain JSON strings (Debezium's
    // EventRouter SMT + StringConverter, see
    // infra/kafka-connect/event-outbox-connector.json) — no Avro/Schema
    // Registry involved on this consumer side, unlike event-service's
    // producer-side kafka-avro-serializer dependency.

    // Elasticsearch Testcontainers isn't wired anywhere in this repo yet —
    // root build.gradle.kts only gives every module the Postgres/
    // junit-jupiter Testcontainers modules other services actually need.
    // Added here, not at root, since search-service is the only module
    // that needs it.
    testImplementation("org.testcontainers:elasticsearch:1.21.4")
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

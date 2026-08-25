// event-service: events/sessions, Notify Me (ADR-021), outbox producer (ADR-007).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
    implementation("software.amazon.awssdk:s3:2.28.11") // pre-signed URLs for trailer upload (ADR-017)
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

// First-and-only @SpringBootApplication main class in this module, so
// bootJar is re-enabled and the plain jar disabled — same pattern as
// auth-service's build.gradle.kts (the root build turns bootJar off by
// default for every module until it actually has an entry point).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = false
}

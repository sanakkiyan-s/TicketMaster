// user-service: profile + preferences + PCI-safe payment-method display
// refs (ADR-011). Plain CRUD, no unusual concurrency (see wiki
// projects/user-service.md).
dependencies {
    // ADR-034: the OpenAPI spec is GENERATED from the controllers, never
    // hand-written — see auth-service's build.gradle.kts for the full
    // rationale. Same artifact/version auth-service pins.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    // No jjwt dependency: shared/CurrentUserResolver only needs to
    // base64url-decode the JWT payload segment and read it as JSON, which
    // java.util.Base64 (JDK) plus Jackson (already pulled in by
    // spring-boot-starter-web) does without pulling in a JWT parsing
    // library whose whole point is signature verification — a
    // responsibility that belongs to api-gateway per ADR-009, not here.
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

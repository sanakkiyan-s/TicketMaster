// auth-service (ADR-012): JWT issuance, key rotation, revocation.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis") // JWKS-adjacent caching if needed
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.springframework.kafka:spring-kafka") // auth.revocation topic (ADR-012 amendment)
}

// First module with a @SpringBootApplication main class, so the first to
// re-enable the executable jar that the root build disables for every
// module. The root comment states exactly this contract: each service
// turns bootJar back on once it actually has an entry point.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

// Plain jar off: with bootJar enabled, both tasks write to build/libs and
// collide on the same archive name.
tasks.named<Jar>("jar") {
    enabled = false
}

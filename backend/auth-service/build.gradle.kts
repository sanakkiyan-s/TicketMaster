// auth-service (ADR-012): JWT issuance, key rotation, revocation.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    // LoginAttemptLimiter: per-username failed-login counter. The
    // gateway's IP-keyed leaky bucket is the volumetric shield; this is
    // the account-abuse control, and it needs the parsed request body
    // (the username), which only exists once Spring MVC has deserialized
    // it here - the gateway is a streaming proxy that never buffers it.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.springframework.kafka:spring-kafka") // auth.revocation topic (ADR-012 amendment)

    // ADR-034: the OpenAPI spec is GENERATED from the controllers, never
    // hand-written, so it cannot drift from the code the way a separate
    // document would. webmvc (not webflux) — api-gateway is reactive,
    // every downstream service is servlet-based.
    //
    // Per-service rather than gateway-only: Spring Cloud Gateway proxies
    // routes, it does not see RegisterRequest's constraints. A spec
    // generated at the gateway would be empty or hand-written; both
    // defeat the point. The gateway aggregates these instead.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    // spring-vault-core (VaultTemplate), NOT spring-cloud-vault-config.
    //
    // spring-cloud-vault-config loads secrets as a Spring PropertySource at
    // bootstrap, which is frozen for the life of the context. ADR-012's
    // rotation changes the signing key set WHILE the service runs — phase 1
    // publishes K2 before anything signs with it — so the key set has to be
    // re-readable at runtime. A PropertySource cannot express that; a
    // VaultTemplate read on an interval can.
    //
    // Version pinned: Boot manages spring-vault only via the Spring Cloud Vault
    // BOM, which this project does not import.
    implementation("org.springframework.vault:spring-vault-core:3.1.2")

    testImplementation("org.testcontainers:vault:1.21.4")
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

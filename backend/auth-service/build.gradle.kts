// auth-service (ADR-012): JWT issuance, key rotation, revocation.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis") // JWKS-adjacent caching if needed
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.springframework.kafka:spring-kafka") // auth.revocation topic (ADR-012 amendment)
}

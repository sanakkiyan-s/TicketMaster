// api-gateway (ADR-023, ADR-032, ADR-034): Spring Cloud Gateway is
// reactive (WebFlux) — excludes the common servlet-stack starter the
// root build applies to every other module.
configurations {
    all {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-data-jpa")

        // gRPC too. The root build puts grpc-spring-boot-starter on all 15
        // modules for ADR-023's internal service-to-service calls, but the
        // gateway is not one of those callers - it proxies HTTP and holds no
        // stubs.
        //
        // Leaving it on is not merely unused weight, it breaks startup:
        // Spring Cloud Gateway's GatewayAutoConfiguration sees io.grpc on the
        // classpath, activates its JsonToGrpc filter, and then fails with
        // NoClassDefFoundError io/grpc/netty/NettyChannelBuilder because the
        // netty transport is not present. Half a gRPC stack is worse than
        // none.
        exclude(group = "net.devh", module = "grpc-spring-boot-starter")
        exclude(group = "io.grpc")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // 4.3.x is the 2025.0 release train, which is the one built for Boot 3.5.
    // 4.1.5 (2023.0) fails at startup with an explicit compatibility check:
    // "Spring Boot [3.5.6] is not compatible with this Spring Cloud release
    // train". Worth knowing that check exists - it turns a version mismatch
    // into a clear message instead of a NoSuchMethodError months later.
    implementation("org.springframework.cloud:spring-cloud-starter-gateway:4.3.0")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.9") // ADR-034

    // Verification only - the gateway holds no private key and cannot mint a
    // token. jjwt-impl and jjwt-jackson are runtimeOnly so nothing here can
    // accidentally compile against a signing API.
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
}

// Has a main class now, so the executable jar comes back on - the root build
// disables it for every module until a service has an entry point.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = false
}

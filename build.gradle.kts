plugins {
    java
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.protobuf") version "0.9.4" apply false
    // Every Kafka-producing service scaffold already declares
    // kafka-avro-serializer (event/inventory/booking/payment/ticket/
    // notification/fraud/analytics/media-service) - the schema codegen
    // plugin was the missing piece making that usable. apply false here,
    // applied per-module (inventory-service first) same as protobuf above.
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1" apply false
}

allprojects {
    group = "com.ticketmaster"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "implementation"("org.springframework.boot:spring-boot-starter-web")
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("org.springframework.boot:spring-boot-starter-validation")
        "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
        "runtimeOnly"("org.postgresql:postgresql")
        "implementation"("org.flywaydb:flyway-core")
        "implementation"("org.flywaydb:flyway-database-postgresql")

        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.testcontainers:junit-jupiter:1.21.4")
        "testImplementation"("org.testcontainers:postgresql:1.21.4")
        "testImplementation"(platform("org.junit:junit-bom:5.11.0"))

        // gRPC internal service-to-service calls (ADR-023)
        "implementation"("net.devh:grpc-spring-boot-starter:3.1.0.RELEASE")
        "implementation"("io.grpc:grpc-services:1.66.0")
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        // Docker Engine 29.x rejects the API version docker-java (via
        // Testcontainers) negotiates by default, answering /info with
        // HTTP 400 and a stub body. Every Testcontainers-backed test in
        // every module hits this, so the pin belongs here, not per
        // service. Remove once Testcontainers ships a client that
        // negotiates correctly against Engine 29+.
        systemProperty("api.version", "1.44")

        // -1 disables the gRPC server. The root build gives every module
        // grpc-spring-boot-starter (ADR-023), which binds 9090 on context
        // startup; each @SpringBootTest class boots its own context, so
        // the second one fails with "Address already in use". No module
        // serves gRPC yet.
        //
        // A system property, NOT a src/test/resources/application.yml:
        // Spring resolves classpath:/application.yml to the FIRST match on
        // the classpath, so a test-side file does not merge with the
        // service's config — it replaces it wholesale, and the service
        // then runs its tests against defaults it never configured. That
        // failure is silent. This override cannot shadow anything.
        systemProperty("grpc.server.port", "-1")

        // Testcontainers logs every container lifecycle event at INFO,
        // which buries real assertion failures in the report.
        systemProperty("logging.level.org.testcontainers", "WARN")
        systemProperty("logging.level.com.github.dockerjava", "WARN")
    }

    // No module has a @SpringBootApplication main class yet (Phase 0 is
    // platform bootstrap only, ADR-036). bootJar cannot resolve a main
    // class and would fail `build` for all 15 modules, so it stays off
    // until a service actually gets an entry point — each service
    // re-enables it in its own build file at that point. This keeps
    // Milestone 1's "CI skeleton green on an empty service" achievable.
    tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
        enabled = false
    }
    tasks.named<Jar>("jar") {
        enabled = true
    }
}

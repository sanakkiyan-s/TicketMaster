plugins {
    java
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.protobuf") version "0.9.4" apply false
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
        "testImplementation"("org.testcontainers:junit-jupiter:1.20.1")
        "testImplementation"("org.testcontainers:postgresql:1.20.1")
        "testImplementation"(platform("org.junit:junit-bom:5.11.0"))

        // gRPC internal service-to-service calls (ADR-023)
        "implementation"("net.devh:grpc-spring-boot-starter:3.1.0.RELEASE")
        "implementation"("io.grpc:grpc-services:1.66.0")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
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

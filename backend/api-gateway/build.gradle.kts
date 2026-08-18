// api-gateway (ADR-023, ADR-032, ADR-034): Spring Cloud Gateway is
// reactive (WebFlux) — excludes the common servlet-stack starter the
// root build applies to every other module.
configurations {
    all {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-data-jpa")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway:4.1.5")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.9") // ADR-034
}

// inventory-service: seat-lock concurrency core (ADR-002/004). Highest
// priority, highest complexity module per the implementation roadmap.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

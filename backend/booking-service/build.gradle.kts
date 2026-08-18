// booking-service: Saga orchestration (ADR-006), Idempotency-Key (ADR-025).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

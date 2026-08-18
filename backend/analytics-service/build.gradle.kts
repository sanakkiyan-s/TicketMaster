// analytics-service: organizer dashboards, scoped by organizer_id (ADR-030).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

// fraud-service: velocity/bulk-limit scoring, fail-open by design (ADR-014).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

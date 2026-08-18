// event-service: events/sessions, Notify Me (ADR-021), outbox producer (ADR-007).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
    implementation("software.amazon.awssdk:s3:2.28.11") // pre-signed URLs for trailer upload (ADR-017)
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

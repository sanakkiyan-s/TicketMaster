// notification-service: email/SMS/push, FCM topic fan-out (ADR-021),
// idempotent Kafka consumer (ADR-031).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
    implementation("com.google.firebase:firebase-admin:9.4.1")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

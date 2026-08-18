// payment-service: append-only ledger (ADR-020), Stripe SAQ A (ADR-011),
// PgBouncer-pooled (ADR-024), reconciliation/dispute (ADR-035).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
    implementation("com.stripe:stripe-java:29.2.0")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

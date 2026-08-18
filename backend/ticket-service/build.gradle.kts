// ticket-service: rotating barcode (ADR-014), transfer/resale (ADR-029).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

// search-service: Kafka-fed Elasticsearch projection (CQRS read model).
dependencies {
    implementation("org.springframework.data:spring-data-elasticsearch")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

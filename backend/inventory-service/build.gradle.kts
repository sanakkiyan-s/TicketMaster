// inventory-service: seat-lock concurrency core (ADR-002/004). Highest
// priority, highest complexity module per the implementation roadmap.
//
// First real Avro producer in this codebase - every Kafka-producing
// service was scaffolded with kafka-avro-serializer but none actually
// generate/produce Avro yet (see root build.gradle.kts's comment on the
// codegen plugin). Schemas live in src/main/avro/*.avsc; the plugin
// generates Java classes into build/generated-main-avro-java at compile
// time, consumed by the seat-event producer.
apply(plugin = "com.github.davidmc24.gradle.plugin.avro")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = false
}

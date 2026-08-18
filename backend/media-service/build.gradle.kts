// media-service: object storage, multipart/resumable upload, FFmpeg
// transcoding via Kafka job topic (ADR-017, chunked-upload amendment).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-avro-serializer:7.7.1")
    implementation("software.amazon.awssdk:s3:2.28.11")
}

repositories {
    maven { url = uri("https://packages.confluent.io/maven/") }
}

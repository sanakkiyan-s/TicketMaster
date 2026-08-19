package com.ticketmaster.gateway.jwt.revocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real broker/consumer semantics that a mock cannot prove:
 * partition assignment, seek-to-beginning catch-up, and the readiness
 * transition itself (ADR-012's fail-closed carve-out).
 *
 * Testcontainers, not a stub, for the same reason auth-service's Vault test
 * and this project's Postgres/Redis tests use a real container - the thing
 * worth proving is real broker behaviour, not a mock's idea of it.
 */
@Testcontainers
class RevocationConsumerIT {

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static RevocationProperties properties;

    private RevocationStore store;
    private RevocationConsumer consumer;
    private RevocationHealthIndicator health;
    private String topic;

    @BeforeAll
    static void baseProperties() {
        properties = new RevocationProperties(
                null, "api-gateway-revocation-it",
                Duration.ofMinutes(10), Duration.ofMinutes(1));
    }

    @BeforeEach
    void setUp() throws Exception {
        // A fresh topic per test: this consumer always replays from
        // earliest (ADR-012), so a shared topic would leak state between
        // tests the same way a shared consumer group would.
        topic = "auth.revocation." + UUID.randomUUID();
        createTopic(topic);

        properties = new RevocationProperties(
                topic, "api-gateway-revocation-it",
                Duration.ofMinutes(10), Duration.ofMinutes(1));

        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(List.of(kafka.getBootstrapServers()));

        store = new RevocationStore();
        consumer = new RevocationConsumer(store, properties, kafkaProperties, new ObjectMapper());
        health = new RevocationHealthIndicator(consumer);
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
    }

    private void createTopic(String name) throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    private void publish(String key, String json) throws Exception {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>(topic, key, json)).get(30, TimeUnit.SECONDS);
        }
    }

    private String revocationJson(Instant revokeBefore, String reason) {
        return "{\"revokeBefore\":" + revokeBefore.toEpochMilli() + ",\"reason\":\"" + reason + "\"}";
    }

    /** No Awaitility dependency in this module - a small manual poll suffices. */
    private void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    @Test
    void readinessIsDownBeforeCatchUpAndUpAfter() throws Exception {
        // Nothing published yet, consumer not started: DOWN.
        assertFalse(consumer.isCaughtUp());
        assertTrue(health.health().getStatus().getCode().equals("DOWN"));

        consumer.start();

        waitUntil(Duration.ofSeconds(30), consumer::isCaughtUp);

        assertTrue(consumer.isCaughtUp());
        assertTrue(health.health().getStatus().getCode().equals("UP"));
    }

    @Test
    void aUserKeyedMessageRevokesEveryTokenForThatUser() throws Exception {
        String userId = "user:" + UUID.randomUUID();
        Instant revokeBefore = Instant.now();
        publish(userId, revocationJson(revokeBefore, "banned for fraud"));

        consumer.start();
        waitUntil(Duration.ofSeconds(30), consumer::isCaughtUp);

        // Issued before the ban - rejected.
        assertTrue(store.isRevoked(userId, revokeBefore.minus(Duration.ofMinutes(1))));
        // Issued after the ban (a fresh login) - allowed.
        assertFalse(store.isRevoked(userId, revokeBefore.plus(Duration.ofMinutes(1))));
    }

    @Test
    void aSessionKeyedMessageRevokesOnlyThatSession() throws Exception {
        String sessionKey = "session:" + UUID.randomUUID();
        String unrelatedUserKey = "user:" + UUID.randomUUID();
        Instant revokeBefore = Instant.now();
        publish(sessionKey, revocationJson(revokeBefore, "single device logout"));

        consumer.start();
        waitUntil(Duration.ofSeconds(30), consumer::isCaughtUp);

        assertTrue(store.isRevoked(sessionKey, revokeBefore.minus(Duration.ofMinutes(1))));
        assertFalse(store.isRevoked(unrelatedUserKey, revokeBefore.minus(Duration.ofMinutes(1))));
    }

    @Test
    void aTombstoneRemovesTheEntry() throws Exception {
        String userId = "user:" + UUID.randomUUID();
        Instant revokeBefore = Instant.now();
        publish(userId, revocationJson(revokeBefore, "banned"));
        publish(userId, null); // Kafka tombstone

        consumer.start();
        waitUntil(Duration.ofSeconds(30), consumer::isCaughtUp);

        assertFalse(store.isRevoked(userId, revokeBefore.minus(Duration.ofMinutes(1))));
    }
}

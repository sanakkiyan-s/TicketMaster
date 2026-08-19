package com.ticketmaster.gateway.jwt.revocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Materializes {@link RevocationStore} from the {@code auth.revocation}
 * topic (ADR-012).
 *
 * A raw {@link KafkaConsumer} on its own thread, deliberately NOT a
 * {@code @KafkaListener} container: readiness needs a precise answer to
 * "has this instance finished reading everything that existed at startup",
 * which means comparing the consumer's position against the partition end
 * offsets recorded before the first poll. A listener container's idle-event
 * heuristics approximate that; manual assign/seekToBeginning/endOffsets
 * proves it.
 *
 * {@code assign()}, not {@code subscribe()} - group-managed partition
 * assignment would split the topic's partitions across gateway instances in
 * the same group, so no single instance would hold the full map. Every
 * instance must read every partition. The consumer group id is still set
 * (unique per JVM, ADR-012's "own consumer group") purely so external
 * tooling can see per-instance lag; it plays no role in partition
 * assignment or offset resumption here, because offsets are never committed
 * - every restart deliberately replays the whole compacted log from
 * earliest rather than resuming a prior instance's position.
 */
@Component
public class RevocationConsumer {

    private static final Logger log = LoggerFactory.getLogger(RevocationConsumer.class);

    private final RevocationStore store;
    private final RevocationProperties properties;
    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean caughtUp = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "revocation-consumer");
        t.setDaemon(true);
        return t;
    });

    RevocationConsumer(RevocationStore store, RevocationProperties properties,
            KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        this.store = store;
        this.properties = properties;
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Readiness gate (ADR-012's fail-closed carve-out): false until the
     * initial catch-up read completes. A later disconnect never flips this
     * back to false - see {@link #pollLoop}.
     */
    public boolean isCaughtUp() {
        return caughtUp.get();
    }

    @PostConstruct
    void start() {
        executor.submit(this::run);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        executor.shutdown();
    }

    private void run() {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            List<TopicPartition> partitions = consumer.partitionsFor(properties.topic()).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            pollLoop(consumer, partitions, endOffsets);
        } catch (RuntimeException e) {
            // A startup failure to connect is the one case this feature
            // deliberately leaves readiness DOWN for (ADR-012) - do not
            // retry from here silently, a crash-looped pod is the correct,
            // visible signal that Kafka was unreachable at startup.
            log.error("revocation consumer could not start against topic {}; "
                    + "readiness will not pass until it recovers (ADR-012 fail-closed carve-out)",
                    properties.topic(), e);
        }
    }

    private void pollLoop(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions,
            Map<TopicPartition, Long> endOffsets) {
        markCaughtUpIfReady(consumer, partitions, endOffsets);

        while (running.get()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    applyRecord(record);
                }
                markCaughtUpIfReady(consumer, partitions, endOffsets);
            } catch (WakeupException e) {
                break;
            } catch (RuntimeException e) {
                // Mid-flight loss, not a startup failure: keep serving the
                // last-known map and alarm loudly (ADR-012 - "degrading
                // toward staleness is acceptable, degrading toward empty is
                // not"). Readiness, once true, is deliberately NOT touched
                // here.
                log.error("ALERT revocation consumer lost its connection to Kafka mid-flight; "
                        + "serving the last-known revocation map ({} entries) and will keep retrying. "
                        + "This instance stays ready - see ADR-012 (degrade toward staleness, not empty).",
                        store.size(), e);
                sleepBeforeRetry();
            }
        }
    }

    private void markCaughtUpIfReady(Consumer<String, String> consumer, List<TopicPartition> partitions,
            Map<TopicPartition, Long> endOffsets) {
        if (caughtUp.get()) {
            return;
        }
        boolean allCaughtUp = partitions.stream()
                .allMatch(p -> consumer.position(p) >= endOffsets.getOrDefault(p, 0L));
        if (allCaughtUp) {
            caughtUp.set(true);
            log.info("revocation consumer caught up on {} - {} entr{} live", properties.topic(),
                    store.size(), store.size() == 1 ? "y" : "ies");
        }
    }

    private void applyRecord(ConsumerRecord<String, String> record) {
        String key = record.key();
        if (key == null) {
            return;
        }
        if (record.value() == null) {
            // Kafka tombstone - the compacted topic's own way of expiring a
            // key. auth-service is not required to emit these for this
            // slice to work (RevocationCleanupScheduler tombstones locally
            // on TTL too), but honour one if it arrives.
            store.remove(key);
            return;
        }
        try {
            RevocationMessage message = objectMapper.readValue(record.value(), RevocationMessage.class);
            store.apply(key, new RevocationEntry(Instant.ofEpochMilli(message.revokeBefore()), message.reason()));
        } catch (Exception e) {
            log.error("could not parse auth.revocation record for key={}; this ban/logout will NOT take effect "
                    + "on this instance until a corrected record is published", key, e);
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Duration.ofSeconds(1).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupPrefix() + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    /** {@code { revokeBefore: <epoch millis>, reason: "<string>" } } - the fixed message contract. */
    private record RevocationMessage(long revokeBefore, String reason) {
    }
}

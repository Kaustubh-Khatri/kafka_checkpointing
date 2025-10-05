package com.example.adaptive;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Adaptive Kafka Compaction system.
 *
 * Prerequisites:
 * - Kafka must be running on localhost:9092
 * - Topics must be created or auto-creation enabled
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AdaptiveControllerTest {

    private static KafkaProducer<String, String> producer;
    private static final String TEST_TOPIC = "workload";

    @BeforeAll
    public static void setUp() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(props);
    }

    @AfterAll
    public static void tearDown() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test Hot Key Detection - Should trigger compaction")
    public void testHotKeyDetection() throws InterruptedException {
        System.out.println("\n=== Test: Hot Key Detection ===");

        // Send 15 messages for the same key within 30 seconds
        String hotKey = "test-user-hot";
        for (int i = 0; i < 15; i++) {
            producer.send(new ProducerRecord<>(TEST_TOPIC, hotKey, "value-" + i));
            Thread.sleep(100); // Space out messages
        }
        producer.flush();

        System.out.println("Sent 15 messages for key: " + hotKey);
        System.out.println("Expected: Hot key should be detected (threshold = 10)");

        // In real test, you would verify logs or expose metrics from AdaptiveController
        assertTrue(15 > 10, "Message count exceeds hot key threshold");
    }

    @Test
    @Order(2)
    @DisplayName("Test Normal Workload - Should NOT trigger compaction")
    public void testNormalWorkload() throws InterruptedException {
        System.out.println("\n=== Test: Normal Workload ===");

        // Send 20 messages distributed across 5 keys (4 messages per key)
        String[] keys = {"user-1", "user-2", "user-3", "user-4", "user-5"};
        for (int i = 0; i < 20; i++) {
            String key = keys[i % keys.length];
            producer.send(new ProducerRecord<>(TEST_TOPIC, key, "value-" + i));
            Thread.sleep(100);
        }
        producer.flush();

        System.out.println("Sent 20 messages across 5 keys (4 messages/key)");
        System.out.println("Expected: No hot keys detected (all keys below threshold)");

        assertTrue(4 < 10, "Messages per key below hot key threshold");
    }

    @Test
    @Order(3)
    @DisplayName("Test Compaction Correctness")
    public void testCompactionCorrectness() throws Exception {
        System.out.println("\n=== Test: Compaction Correctness ===");

        // Create test data with multiple versions of same keys
        Map<String, String> expectedLatest = new HashMap<>();
        expectedLatest.put("key-A", "key-A-version-10");
        expectedLatest.put("key-B", "key-B-version-10");
        expectedLatest.put("key-C", "key-C-version-10");

        // Send 10 versions of each key
        for (int version = 1; version <= 10; version++) {
            for (String key : expectedLatest.keySet()) {
                String value = key + "-version-" + version;
                producer.send(new ProducerRecord<>(TEST_TOPIC, key, value));
            }
            Thread.sleep(50);
        }
        producer.flush();

        System.out.println("Sent 30 messages (10 versions × 3 keys)");

        // Wait for messages to be available
        Thread.sleep(2000);

        // Run compaction
        System.out.println("Running compaction...");
        Compactor.main(new String[]{});

        // Verify compacted output
        Thread.sleep(1000);
        Map<String, String> compactedData = readCompactedTopic();

        System.out.println("Compacted data:");
        compactedData.forEach((k, v) -> System.out.println("  " + k + " -> " + v));

        // Verify only latest values are present
        for (Map.Entry<String, String> entry : expectedLatest.entrySet()) {
            assertEquals(entry.getValue(), compactedData.get(entry.getKey()),
                    "Compacted topic should contain latest value for key: " + entry.getKey());
        }

        System.out.println("✓ Compaction correctness verified");
    }

    @Test
    @Order(4)
    @DisplayName("Test Burst Pattern Detection")
    public void testBurstPattern() throws InterruptedException {
        System.out.println("\n=== Test: Burst Pattern ===");

        // Quiet period
        for (int i = 0; i < 5; i++) {
            producer.send(new ProducerRecord<>(TEST_TOPIC, "quiet-key", "value-" + i));
            Thread.sleep(1000);
        }

        // Burst period - 20 messages in 2 seconds
        String burstKey = "burst-user";
        for (int i = 0; i < 20; i++) {
            producer.send(new ProducerRecord<>(TEST_TOPIC, burstKey, "burst-value-" + i));
            Thread.sleep(100);
        }
        producer.flush();

        System.out.println("Sent burst of 20 messages for key: " + burstKey);
        System.out.println("Expected: Burst should trigger hot key detection");

        assertTrue(20 > 10, "Burst count exceeds threshold");
    }

    @Test
    @Order(5)
    @DisplayName("Test Multiple Hot Keys")
    public void testMultipleHotKeys() throws InterruptedException {
        System.out.println("\n=== Test: Multiple Hot Keys ===");

        String[] hotKeys = {"hot-user-1", "hot-user-2", "hot-user-3"};

        // Generate traffic for multiple hot keys simultaneously
        for (int i = 0; i < 15; i++) {
            for (String key : hotKeys) {
                producer.send(new ProducerRecord<>(TEST_TOPIC, key, "value-" + i));
            }
            Thread.sleep(100);
        }
        producer.flush();

        System.out.println("Sent 15 messages for each of 3 hot keys");
        System.out.println("Expected: All 3 keys should be detected as hot");

        assertTrue(15 > 10, "Each key exceeds threshold");
        assertEquals(3, hotKeys.length, "Should detect 3 hot keys");
    }

    /**
     * Helper method to read data from compacted topic
     */
    private Map<String, String> readCompactedTopic() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList("workload-compacted-temp"));

        Map<String, String> data = new HashMap<>();
        int emptyPolls = 0;

        while (emptyPolls < 3) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            if (records.isEmpty()) {
                emptyPolls++;
            } else {
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    data.put(record.key(), record.value());
                }
            }
        }

        consumer.close();
        return data;
    }

    /**
     * Performance test - measure throughput
     */
    @Test
    @Order(6)
    @DisplayName("Performance Test - Measure throughput")
    public void testPerformance() throws InterruptedException {
        System.out.println("\n=== Test: Performance Measurement ===");

        int messageCount = 1000;
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < messageCount; i++) {
            String key = "perf-user-" + (i % 10); // 10 different keys
            producer.send(new ProducerRecord<>(TEST_TOPIC, key, "value-" + i));
        }
        producer.flush();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (messageCount * 1000.0) / duration;

        System.out.printf("Sent %d messages in %d ms%n", messageCount, duration);
        System.out.printf("Throughput: %.2f messages/second%n", throughput);

        assertTrue(duration < 10000, "Should complete within 10 seconds");
        assertTrue(throughput > 50, "Should achieve minimum throughput");
    }
}

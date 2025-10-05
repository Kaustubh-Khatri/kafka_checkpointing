package com.example.adaptive;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.Random;

/**
 * Generates test workload data to simulate different access patterns:
 * - Hot keys (high frequency)
 * - Normal keys (medium frequency)
 * - Cold keys (low frequency)
 */
public class TestDataGenerator {
    private final KafkaProducer<String, String> producer;
    private final String topic;

    public TestDataGenerator(String topic) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");  // Wait for leader ack
        props.put(ProducerConfig.RETRIES_CONFIG, 3);  // Retry on failure
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);  // Small batching
        this.producer = new KafkaProducer<>(props);
        System.out.println("Producer initialized for topic: " + topic);
    }

    /**
     * Scenario 1: Generate hot keys that should trigger compaction
     */
    public void generateHotKeyScenario(int durationSeconds) throws InterruptedException {
        System.out.println("\n=== Generating HOT KEY scenario ===");
        System.out.println("This should trigger compaction (>10 accesses per 30s window)");

        long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        int messageCount = 0;
        int successCount = 0;
        int errorCount = 0;

        while (System.currentTimeMillis() < endTime) {
            // Hot key: accessed very frequently
            String hotKey = "user-123";
            String value = "data-" + System.currentTimeMillis();

            producer.send(new ProducerRecord<>(topic, hotKey, value), (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("Send failed: " + exception.getMessage());
                }
            });
            messageCount++;

            // Send 30 messages per second for the hot key
            Thread.sleep(33); // ~30 msg/sec

            if (messageCount % 10 == 0) {
                System.out.printf("Sent %d messages for hot key: %s%n", messageCount, hotKey);
            }
        }

        producer.flush();
        System.out.printf("Hot key scenario complete: %d total messages sent%n", messageCount);
    }

    /**
     * Scenario 2: Generate normal workload (should NOT trigger compaction)
     */
    public void generateNormalWorkload(int durationSeconds) throws InterruptedException {
        System.out.println("\n=== Generating NORMAL workload scenario ===");
        System.out.println("This should NOT trigger compaction (<10 accesses per key per window)");

        long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        int messageCount = 0;
        String[] keys = {"user-1", "user-2", "user-3", "user-4", "user-5"};

        while (System.currentTimeMillis() < endTime) {
            // Rotate through multiple keys
            String key = keys[messageCount % keys.length];
            String value = "data-" + System.currentTimeMillis();

            producer.send(new ProducerRecord<>(topic, key, value));
            messageCount++;

            // Send 5 messages per second total
            Thread.sleep(200);

            if (messageCount % 10 == 0) {
                System.out.printf("Sent %d messages across %d keys%n", messageCount, keys.length);
            }
        }

        producer.flush();
        System.out.printf("Normal workload complete: %d total messages%n", messageCount);
    }

    /**
     * Scenario 3: Mixed workload with multiple hot keys
     */
    public void generateMixedWorkload(int durationSeconds) throws InterruptedException {
        System.out.println("\n=== Generating MIXED workload scenario ===");
        System.out.println("Hot keys: user-A, user-B (should trigger compaction)");
        System.out.println("Normal keys: user-1 through user-10");

        long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        int messageCount = 0;
        Random random = new Random();

        while (System.currentTimeMillis() < endTime) {
            String key;

            // 60% chance of hot keys, 40% chance of normal keys
            if (random.nextDouble() < 0.6) {
                key = random.nextBoolean() ? "user-A" : "user-B";
            } else {
                key = "user-" + random.nextInt(10);
            }

            String value = "data-" + System.currentTimeMillis();
            producer.send(new ProducerRecord<>(topic, key, value));
            messageCount++;

            // Send 10 messages per second
            Thread.sleep(100);

            if (messageCount % 20 == 0) {
                System.out.printf("Sent %d mixed messages%n", messageCount);
            }
        }

        producer.flush();
        System.out.printf("Mixed workload complete: %d total messages%n", messageCount);
    }

    /**
     * Scenario 4: Burst pattern - simulate sudden spike in activity
     */
    public void generateBurstPattern() throws InterruptedException {
        System.out.println("\n=== Generating BURST pattern scenario ===");
        System.out.println("Quiet period followed by sudden burst of hot key access");

        // Quiet period: 10 seconds with low activity
        System.out.println("Phase 1: Quiet period (10s)...");
        for (int i = 0; i < 10; i++) {
            producer.send(new ProducerRecord<>(topic, "user-quiet", "data-" + i));
            Thread.sleep(1000);
        }

        // Burst period: 20 seconds with very high activity on one key
        System.out.println("Phase 2: Burst period (20s) - triggering hot key...");
        int burstCount = 0;
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 15; j++) { // 15 messages per second
                producer.send(new ProducerRecord<>(topic, "user-burst", "data-" + System.currentTimeMillis()));
                burstCount++;
            }
            Thread.sleep(1000);
        }

        producer.flush();
        System.out.printf("Burst pattern complete: %d burst messages%n", burstCount);
    }

    /**
     * Scenario 5: Compaction test - multiple updates to same keys
     */
    public void generateCompactionTestData() throws InterruptedException {
        System.out.println("\n=== Generating COMPACTION test data ===");
        System.out.println("Creating multiple versions of the same keys to test compaction");

        String[] keys = {"key-A", "key-B", "key-C"};

        // Send 20 updates for each key
        for (int version = 1; version <= 20; version++) {
            for (String key : keys) {
                String value = key + "-version-" + version;
                producer.send(new ProducerRecord<>(topic, key, value));
                System.out.printf("Sent %s -> %s%n", key, value);
                Thread.sleep(100);
            }
        }

        producer.flush();
        System.out.println("Compaction test data complete: 60 total messages (20 versions × 3 keys)");
        System.out.println("After compaction, should only have 3 messages (latest version of each key)");
    }

    public void close() {
        System.out.println("Flushing and closing producer...");
        producer.flush();
        producer.close();
        System.out.println("Producer closed successfully");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TestDataGenerator <scenario> [duration_seconds]");
            System.err.println("Scenarios:");
            System.err.println("  hot          - Generate hot key workload (triggers compaction)");
            System.err.println("  normal       - Generate normal workload (no compaction)");
            System.err.println("  mixed        - Generate mixed workload");
            System.err.println("  burst        - Generate burst pattern");
            System.err.println("  compact-test - Generate compaction test data");
            System.exit(1);
        }

        String scenario = args[0];
        int duration = args.length > 1 ? Integer.parseInt(args[1]) : 40;

        TestDataGenerator generator = new TestDataGenerator("workload");

        try {
            switch (scenario) {
                case "hot":
                    generator.generateHotKeyScenario(duration);
                    break;
                case "normal":
                    generator.generateNormalWorkload(duration);
                    break;
                case "mixed":
                    generator.generateMixedWorkload(duration);
                    break;
                case "burst":
                    generator.generateBurstPattern();
                    break;
                case "compact-test":
                    generator.generateCompactionTestData();
                    break;
                default:
                    System.err.println("Unknown scenario: " + scenario);
                    System.exit(1);
            }
        } finally {
            generator.close();
            System.out.println("\nData generation complete!");
        }
    }
}
